// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
#include <jni.h>
#include <string>
#include <opencv2/opencv.hpp>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraCaptureSession.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include "spectrum.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <sstream>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG "CameraNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace cv;
using namespace std;

static Mat stacked; // CV_32FC1 (合計値. 保存時に capCount で割って平均にする)
static int capCount;
static int width;
static int height;

namespace
{
    // fd の中身を先頭から全部読む.
    // pread なので fd のオフセットは動かさない. pipe など seek できない fd は read にフォールバック
    bool readAllFromFd(int fd, vector<uchar> &out)
    {
        out.clear();
        vector<uchar> chunk(64 * 1024);
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

    bool readTextFromFd(int fd, string &out)
    {
        vector<uchar> bytes;
        if (!readAllFromFd(fd, bytes))
            return false;
        out.assign(bytes.begin(), bytes.end());
        return true;
    }

    // write() は一度に全部書けるとは限らないので書き切るまで回す
    bool writeAllToFd(int fd, const uchar *data, size_t size)
    {
        size_t done = 0;
        while (done < size)
        {
            ssize_t n = write(fd, data + done, size - done);
            if (n < 0)
            {
                if (errno == EINTR)
                    continue;
                return false;
            }
            done += (size_t)n;
        }
        return true;
    }

    // FDにバイナリデータを書き込むヘルパー関数
    bool writeMatToFd(int fd, const Mat &mat, const string &ext, const vector<int> &params)
    {
        vector<uchar> buffer;

        // 指定フォーマットでエンコード (メモリ上でバイナリ化). extensionは ".tif" や ".jpg"
        if (!imencode(ext, mat, buffer, params))
            return false;
        if (!writeAllToFd(fd, buffer.data(), buffer.size()))
            return false;

        // データが確実にディスクに書き込まれるように同期 (FUSE 等で失敗しても致命的ではない)
        if (fsync(fd) == -1)
            LOGE("fsync failed: %d", errno);
        return true;
    }

    vector<int> tiffParams()
    {
        return {IMWRITE_TIFF_COMPRESSION, 1}; // no comp
    }

    // 画像ファイルを fd から読み, 1ch の CV_32F に揃えて返す. 失敗時は空の Mat.
    // imdecode は CV_8U の1次元バッファしか受け付けないので CV_8UC1 で包む
    Mat decodeImageFd(int fd)
    {
        vector<uchar> bytes;
        if (!readAllFromFd(fd, bytes) || bytes.empty())
            return Mat();
        Mat img = imdecode(Mat(1, (int)bytes.size(), CV_8UC1, bytes.data()), IMREAD_UNCHANGED);
        if (img.empty() || img.channels() != 1)
            return Mat();
        if (img.depth() != CV_32F)
        {
            Mat f;
            img.convertTo(f, CV_32F);
            return f;
        }
        return img;
    }

    // CV_32FC1 の Mat を共通コアの画像として見る (コピーしない)
    fk::ImageView viewOf(const Mat &m)
    {
        fk::ImageView v;
        v.data = m.ptr<float>(0);
        v.width = m.cols;
        v.height = m.rows;
        v.stride = m.step1();
        return v;
    }

    fk::Cfa toCfa(int cfa)
    {
        return (cfa >= (int)fk::Cfa::RGGB && cfa <= (int)fk::Cfa::Mono) ? (fk::Cfa)cfa : fk::Cfa::GBRG;
    }

    // RAW (Bayer) をデモザイクする OpenCV の変換コード.
    // OpenCV の Bayer の名前は2行目の2,3列目の色なので, 左上から読んだ名前とはずれる (GBRG -> BayerGR)
    int demosaicCode(fk::Cfa cfa)
    {
        switch (cfa)
        {
        case fk::Cfa::RGGB:
            return COLOR_BayerBG2BGR;
        case fk::Cfa::GRBG:
            return COLOR_BayerGB2BGR;
        case fk::Cfa::BGGR:
            return COLOR_BayerRG2BGR;
        case fk::Cfa::Mono:
            return COLOR_GRAY2BGR;
        case fk::Cfa::GBRG:
        default:
            return COLOR_BayerGR2BGR;
        }
    }

    string subtractDark(int fdLight, int fdDark, int fdOut)
    {
        Mat lightMat = decodeImageFd(fdLight);
        if (lightMat.empty())
            return "観測画像(stacked.tif)を読み込めません";
        Mat darkMat = decodeImageFd(fdDark);
        if (darkMat.empty())
            return "ダーク画像(stacked.tif)を読み込めません";
        if (lightMat.size() != darkMat.size())
            return "観測画像とダーク画像のサイズが違います";

        Mat result = lightMat - darkMat;
        if (!writeMatToFd(fdOut, result, ".tif", tiffParams()))
            return "darked.tif の書き込みに失敗しました";
        return "";
    }

    // スペクトルの計算は共通コア (core/Sources/FukasisCoreC/spectrum.cpp) で行う
    string makeCsv(int fdImg, int fdCalib, int fdMeta, int fdSensit, int fdOut, int fol, const fk::SpectrumParams &params)
    {
        Mat img = decodeImageFd(fdImg);
        if (img.empty())
            return "画像を読み込めません";

        string calib, meta, sensit;
        if (!readTextFromFd(fdCalib, calib))
            return "校正データを読めません";
        if (!readTextFromFd(fdMeta, meta))
            return "めただだ、ないです";
        if (!readTextFromFd(fdSensit, sensit))
            return "感度データを読めません";

        string csv;
        string err = fk::makeSpectrum(viewOf(img), fol, calib, meta, sensit, params, csv);
        if (!err.empty())
            return err;
        if (!writeAllToFd(fdOut, (const uchar *)csv.data(), csv.size()))
            return "スペクトルの書き込みに失敗しました";
        if (fsync(fdOut) == -1)
            LOGE("fsync failed: %d", errno);
        return "";
    }

    // C++ の例外を JNI の外に出さない (出すとプロセスごと abort する)
    template <typename F>
    jstring runGuarded(JNIEnv *env, F f)
    {
        string err;
        try
        {
            err = f();
        }
        catch (const std::exception &e)
        {
            err = string("例外: ") + e.what();
        }
        if (!err.empty())
            LOGE("%s", err.c_str());
        return env->NewStringUTF(err.c_str());
    }
}

extern "C"
{
    JNIEXPORT void JNICALL
    Java_com_example_ssa_Cam_prepare(
        JNIEnv *env,
        jobject,
        jint img_width,
        jint img_height)
    {

        width = img_width;
        height = img_height;
        stacked = Mat::zeros(height, width, CV_32FC1);
        capCount = 0;
    }

    // 成功時は空文字列, 失敗時はエラーメッセージを返す
    JNIEXPORT jstring JNICALL
    Java_com_example_ssa_Cam_accumulateImg(
        JNIEnv *env,
        jobject tmp,
        jobject buff,
        jint rowStride,
        jint bufferSize)
    {
        return runGuarded(env, [&]() -> string
                          {
            if (buff == nullptr)
                return "ぬるぽ";
            uint8_t *dataPtr = (uint8_t *)env->GetDirectBufferAddress(buff);
            if (dataPtr == nullptr)
                return "ぬるぽ1";
            if (stacked.empty() || width <= 0 || height <= 0)
                return "えっと…empty…なん、ですけど…";
            if ((int64_t)bufferSize < (int64_t)(height - 1) * rowStride + (int64_t)width * 2)
                return "サイズが小さすぎるんだよね";

            Mat rawMat(height, width, CV_16UC1, (void *)dataPtr, rowStride);
            // accumulate()がCV_16UC1非対応なので
            Mat rawMat32;
            rawMat.convertTo(rawMat32, CV_32FC1);
            if (stacked.size() != rawMat32.size())
                return "サイズがちがうです…";
            accumulate(rawMat32, stacked);
            capCount++;
            return ""; });
    }

    // JNIEXPORT jbyteArray JNICALL
    // Java_com_example_ssa_Cam_processImg(
    //         JNIEnv* env,
    //         jobject tmp,
    //         jstring jfilepath
    //         ){
    //
    //     std::stringstream ss;
    //     if(jfilepath == nullptr){
    //         ss << "ぬるぽ" << endl;
    //         return nullptr;
    //     }
    //
    //
    //     ss << "" << stacked.at<float>(10,10) << "、ですね!" << endl;
    //     Mat stacked16;
    //     //stacked.convertTo(stacked16, CV_32FC1, 1.0 / 65535.0 );
    //     stacked.convertTo(stacked16, CV_16UC1, 16.0 / capCount);
    //
    //     std::vector<unsigned char> buffer;
    //     if(!imencode(".tif", stacked16, buffer)){
    //         return nullptr;
    //     }
    //
    //     jbyteArray resultByte = env->NewByteArray(buffer.size());
    //     env->SetByteArrayRegion(resultByte, 0, buffer.size(), (jbyte*)buffer.data());
    //
    //     /*
    //     Mat result;
    //
    //     stacked.convertTo(*result, CV_16U1, 1.0 / capCount );
    //     stacked.release();
    //     */
    //
    //
    //
    //     //imwrite()
    //
    //     // save csv
    //     const char* filepath = env->GetStringUTFChars(jfilepath, nullptr);
    //     std::fstream csvFile(filepath, std::ios::out);
    //     if(csvFile.is_open()){
    //         csvFile << ss.str();
    //         csvFile.close();
    //     }else{
    //         ss << "file is not opened" << std::endl;
    //     }
    //     env->ReleaseStringUTFChars(jfilepath, filepath);
    //
    //     return resultByte;
    // }

    // 成功時は空文字列, 失敗時はエラーメッセージを返す
    JNIEXPORT jstring JNICALL
    Java_com_example_ssa_Cam_saveImg(
        JNIEnv *env, jobject,
        jint fdTiff,
        jint fdJpeg,
        jint cfa)
    {
        return runGuarded(env, [&]() -> string
                          {
            if (stacked.empty() || capCount <= 0)
                return "スタックされた画像がありません";

            // 合計ではなく平均を保存する (枚数の違う観測/ダーク同士でも減算できるように)
            Mat mean = stacked / (double)capCount;
            if (!writeMatToFd(fdTiff, mean, ".tif", tiffParams()))
                return "stacked.tif の書き込みに失敗しました";

            // 32bit -> 8bit (0-255 に正規化) してデモザイク
            Mat tmp;
            Mat displayMat;
            normalize(mean, tmp, 0, 255, NORM_MINMAX, CV_8UC1);
            cvtColor(tmp, displayMat, demosaicCode(toCfa(cfa)));

            if (!writeMatToFd(fdJpeg, displayMat, ".jpg", {IMWRITE_JPEG_QUALITY, 90}))
                return "stacked.jpg の書き込みに失敗しました";
            return ""; });
    }

    // 成功時は空文字列, 失敗時はエラーメッセージを返す
    JNIEXPORT jstring JNICALL
    Java_com_example_ssa_DarkActivity_processImgs(
        JNIEnv *env, jobject,
        jint fd1,
        jint fd2,
        jint fd3)
    {
        return runGuarded(env, [&]() { return subtractDark(fd1, fd2, fd3); });
    }

    // 成功時は空文字列, 失敗時はエラーメッセージを返す
    JNIEXPORT jstring JNICALL
    Java_com_example_ssa_CsvActivity_makecsv(
        JNIEnv *env, jobject,
        jint fd1,
        jint fd2,
        jint fd3,
        jint fd4,
        jint fd5,
        jint fol,
        jint tMin,
        jint tMax,
        jint bandWidth,
        jdouble bandCenter,
        jint cfa)
    {
        fk::SpectrumParams params;
        params.tMin = tMin;
        params.tMax = tMax;
        params.bandWidth = bandWidth;
        params.bandCenter = bandCenter;
        params.cfa = toCfa(cfa);
        return runGuarded(env, [&]() { return makeCsv(fd1, fd2, fd3, fd4, fd5, fol, params); });
    }
}
