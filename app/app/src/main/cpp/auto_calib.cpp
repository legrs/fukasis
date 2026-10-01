// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
//
// 自動校正 (SpectrumCalibrator) 用のネイティブ処理.
// 画像のデコードと 1次元プロファイルの抽出だけを行い, 0次光推定・ピーク検出・
// 波長カタログとの対応付けは host で単体テストできる Java 側 (SpectrumCalibrator) で行う.
#include <jni.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
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
    // makecsv と同じ: 画像中央から縦に積算する帯の幅と sigma clipping の閾値
    const int BAND_WIDTH = 80;
    const double SIGMA_THRES = 3.0;
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

    // b g r (makecsv と同じ割り当て)
    int bayerChannel(int x, int y)
    {
        if (x % 2 != 0 && y % 2 == 0)
            return 0;
        if (x % 2 == 0 && y % 2 != 0)
            return 2;
        return 1;
    }

    // 列 x の帯 [y1, y2) を, チャネルごとに sigma clipping した平均の和にする (makecsv と同じ計算)
    double columnValue(const cv::Mat &img, int x, int y1, int y2, bool isRaw)
    {
        int count[3] = {0, 0, 0};
        double mean[3] = {0, 0, 0};
        double sigma[3] = {0, 0, 0};
        double sum[3] = {0, 0, 0};
        for (int y = y1; y < y2; y++)
        {
            int ch = isRaw ? bayerChannel(x, y) : 1;
            mean[ch] += img.at<float>(y, x);
            count[ch]++;
        }
        for (int c = 0; c < 3; c++)
            if (count[c] > 0)
                mean[c] /= count[c];
        for (int y = y1; y < y2; y++)
        {
            int ch = isRaw ? bayerChannel(x, y) : 1;
            double d = img.at<float>(y, x) - mean[ch];
            sigma[ch] += d * d;
        }
        for (int c = 0; c < 3; c++)
            if (count[c] > 0)
                sigma[c] = std::sqrt(sigma[c] / count[c]);
        for (int y = y1; y < y2; y++)
        {
            int ch = isRaw ? bayerChannel(x, y) : 1;
            double v = img.at<float>(y, x);
            if (SIGMA_THRES * sigma[ch] < std::fabs(v - mean[ch]))
            {
                count[ch]--;
            }
            else
            {
                sum[ch] += std::max(v, 0.0);
            }
        }
        double total = 0;
        for (int c = 0; c < 3; c++)
            total += sum[c] / std::max(count[c], 1);
        return total;
    }

    // スペクトルの帯が写っている行 (0次光を除いた左 75% の行和が最大の行). 見つからなければ -1
    int detectBandCenter(const cv::Mat &img)
    {
        const int h = img.rows;
        const int xEnd = std::max(1, (int)(img.cols * 0.75));
        std::vector<double> rowSum(h, 0.0);
        for (int y = 0; y < h; y++)
        {
            const float *row = img.ptr<float>(y);
            double s = 0;
            for (int x = 0; x < xEnd; x++)
                s += std::max(row[x], 0.0f);
            rowSum[y] = s;
        }
        // 2行周期の Bayer の段差をならす
        int best = -1;
        double bestVal = 0;
        for (int y = 2; y < h - 2; y++)
        {
            double s = rowSum[y - 2] + rowSum[y - 1] + rowSum[y] + rowSum[y + 1] + rowSum[y + 2];
            if (s > bestVal)
            {
                bestVal = s;
                best = y;
            }
        }
        return best;
    }
}

extern "C"
{
    // 画像を解析して [width, height, bandCenterY, profile[0], ..., profile[width-1]] を返す.
    // profile[x] は makecsv と同じ中央 80px 帯の列ごとの値. 失敗時は null
    JNIEXPORT jdoubleArray JNICALL
    Java_com_example_ssa_SpectrumCalibrator_analyzeImageNative(JNIEnv *env, jclass, jint fd)
    {
        try
        {
            bool isRaw = true;
            cv::Mat img = decodeAsFloat(fd, isRaw);
            if (img.empty() || img.rows < 1 || img.cols < 1)
                return nullptr;
            const int h = img.rows;
            const int w = img.cols;
            int y1 = std::max(0, h / 2 - BAND_WIDTH / 2);
            int y2 = std::min(h, h / 2 + BAND_WIDTH / 2);

            std::vector<double> out(HEADER_SIZE + w);
            out[0] = w;
            out[1] = h;
            out[2] = detectBandCenter(img);
            for (int x = 0; x < w; x++)
                out[HEADER_SIZE + x] = columnValue(img, x, y1, y2, isRaw);

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
