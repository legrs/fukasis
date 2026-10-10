// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
//
// 自動校正 (SpectrumCalibrator) 用のネイティブ処理.
// 画像のデコードだけを行い (1次元プロファイルの抽出は共通コア), 0次光推定・ピーク検出・
// 波長カタログとの対応付けは host で単体テストできる Java 側 (SpectrumCalibrator) で行う.
#include <jni.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include "spectrum.hpp"
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <string>
#include <vector>
#include <unistd.h>

#define LOG_TAG "AutoCalib"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace
{
    // 返す配列の先頭に置く値の数 (width, height, bandCenterY)
    const int HEADER_SIZE = 3;

    // fd の中身を先頭から全部読む. pread なので fd のオフセットは動かさない
    bool readAllFromFd(int fd, std::vector<uchar> &out)
    {
        out.clear();
        std::vector<uchar> chunk(64 * 1024);
        off_t offset = 0;
        bool seekable = true;
        for (;;)
        {
            ssize_t n = seekable ? pread(fd, chunk.data(), chunk.size(), offset)
                                 : read(fd, chunk.data(), chunk.size());
            if (n < 0)
            {
                if (errno == EINTR)
                    continue;
                if (seekable && errno == ESPIPE && offset == 0)
                {
                    seekable = false;
                    continue;
                }
                return false;
            }
            if (n == 0)
                break;
            out.insert(out.end(), chunk.begin(), chunk.begin() + n);
            offset += n;
        }
        return true;
    }

    // 1ch の CV_32F に揃えて返す. Bayer のままの 1ch 画像なら isRaw = true
    cv::Mat decodeAsFloat(int fd, bool &isRaw)
    {
        std::vector<uchar> bytes;
        if (!readAllFromFd(fd, bytes) || bytes.empty())
            return cv::Mat();
        // imdecode は CV_8U の1次元バッファしか受け付けない
        cv::Mat img = cv::imdecode(cv::Mat(1, (int)bytes.size(), CV_8UC1, bytes.data()), cv::IMREAD_UNCHANGED);
        if (img.empty())
            return cv::Mat();
        isRaw = img.channels() == 1;
        if (!isRaw)
        {
            cv::Mat gray;
            cv::cvtColor(img, gray, img.channels() == 4 ? cv::COLOR_BGRA2GRAY : cv::COLOR_BGR2GRAY);
            img = gray;
        }
        cv::Mat f;
        img.convertTo(f, CV_32F);
        return f;
    }

}

extern "C"
{
    // 画像を解析して [width, height, bandCenterY, profile[0], ..., profile[width-1]] を返す.
    // profile[x] は makecsv と同じ帯 (bandWidth, bandCenter) の列ごとの値 (共通コアの columnProfile). 失敗時は null
    JNIEXPORT jdoubleArray JNICALL
    Java_com_example_ssa_SpectrumCalibrator_analyzeImageNative(JNIEnv *env, jclass, jint fd,
                                                               jint bandWidth, jdouble bandCenter, jint cfa)
    {
        try
        {
            bool isRaw = true;
            cv::Mat img = decodeAsFloat(fd, isRaw);
            if (img.empty() || img.rows < 1 || img.cols < 1)
                return nullptr;
            fk::ImageView view;
            view.data = img.ptr<float>(0);
            view.width = img.cols;
            view.height = img.rows;
            view.stride = img.step1();
            fk::SpectrumParams params;
            params.bandWidth = bandWidth;
            params.bandCenter = bandCenter;
            // カラー画像 (jpg など) はグレーにしてあるので Bayer として扱わない
            params.cfa = !isRaw ? fk::Cfa::Mono
                                : (cfa >= (int)fk::Cfa::RGGB && cfa <= (int)fk::Cfa::Mono) ? (fk::Cfa)cfa
                                                                                          : fk::Cfa::GBRG;

            std::vector<double> profile = fk::columnProfile(view, params);
            std::vector<double> out(HEADER_SIZE + profile.size());
            out[0] = view.width;
            out[1] = view.height;
            out[2] = fk::detectBandCenter(view);
            std::copy(profile.begin(), profile.end(), out.begin() + HEADER_SIZE);

            jdoubleArray arr = env->NewDoubleArray((jsize)out.size());
            if (arr == nullptr)
                return nullptr;
            env->SetDoubleArrayRegion(arr, 0, (jsize)out.size(), out.data());
            return arr;
        }
        catch (const std::exception &e)
        {
            // C++ の例外を JNI の外に出すとプロセスごと abort する
            LOGE("analyzeImageNative: %s", e.what());
            return nullptr;
        }
    }
}
