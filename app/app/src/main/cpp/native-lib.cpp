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
    // スペクトルを切り出す範囲 (fol からの距離, pixel)
    const int T_MIN = 1800;
    const int T_MAX = 2800;
    // 画像中央から縦に積算する帯の幅 (pixel)
    const int BAND_WIDTH = 80;
    const double SIGMA_THRES = 3.0;

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

    bool readLinesFromFd(int fd, vector<string> &lines)
    {
        vector<uchar> bytes;
        if (!readAllFromFd(fd, bytes))
            return false;
        lines.clear();
        string cur;
        for (uchar c : bytes)
        {
            if (c == '\n')
            {
                lines.push_back(cur);
                cur.clear();
            }
            else if (c != '\r')
            {
                cur.push_back((char)c);
            }
        }
        if (!cur.empty())
            lines.push_back(cur);
        return true;
    }

    bool isBlank(const string &s)
    {
        return s.find_first_not_of(" \t") == string::npos;
    }

    // カンマ区切りの数値を全部パースする. 1つでも数値でなければ false (例外は投げない)
    bool parseNumbers(const string &line, vector<double> &out)
    {
        out.clear();
        stringstream ss(line);
        string field;
        while (getline(ss, field, ','))
        {
            const char *begin = field.c_str();
            char *end = nullptr;
            errno = 0;
            double v = strtod(begin, &end);
            if (end == begin || errno == ERANGE || !std::isfinite(v))
                return false;
            while (*end == ' ' || *end == '\t')
                end++;
            if (*end != '\0')
                return false;
            out.push_back(v);
        }
        return !out.empty();
    }

    // b g r
    int bayerChannel(int x, int y)
    {
        if (x % 2 != 0 && y % 2 == 0)
            return 0;
        if (x % 2 == 0 && y % 2 != 0)
            return 2;
        return 1;
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

    string makeCsv(int fdImg, int fdCalib, int fdMeta, int fdSensit, int fdOut, int fol)
    {
        // img ----------------------------------------------------------
        Mat img = decodeImageFd(fdImg);
        if (img.empty())
            return "画像を読み込めません";
        const int h = img.rows;
        const int w = img.cols;
        if (fol <= 0 || fol >= w)
            return "0次光の位置(fol)が画像の範囲外です: " + to_string(fol);

        vector<string> lines;
        vector<double> nums;

        // calibdata ----------------------------------------------------------
        if (!readLinesFromFd(fdCalib, lines))
            return "校正データを読めません";
        vector<vector<double>> calibRows;
        for (const string &line : lines)
        {
            if (isBlank(line))
                continue;
            if (!parseNumbers(line, nums) || nums.size() < 4)
                return "校正データの形式が不正です: " + line;
            calibRows.push_back(nums);
            if (calibRows.size() == 2)
                break;
        }
        if (calibRows.size() < 2)
            return "校正データがない";

        double t_ref[4];
        double c_ref[4];
        double i_deno[4];
        for (int i = 0; i < 4; i++)
        {
            t_ref[i] = calibRows[0][i];
            c_ref[i] = calibRows[1][i];
        }
        for (int j = 0; j < 4; j++)
        {
            i_deno[j] = 1.0;
            for (int k = 0; k < 4; k++)
            {
                if (k != j)
                {
                    i_deno[j] *= (t_ref[j] - t_ref[k]);
                }
            }
            if (i_deno[j] == 0)
                return "校正データの画素位置が重複しています";
        }

        LOGI("t_ref : %f , %f , %f , %f", t_ref[0], t_ref[1], t_ref[2], t_ref[3]);
        LOGI("c_ref : %f , %f , %f , %f", c_ref[0], c_ref[1], c_ref[2], c_ref[3]);

        // observation infomation --------------------------------------------------------------
        if (!readLinesFromFd(fdMeta, lines) || lines.empty())
            return "めただだ、ないです";
        const string header = lines[0];

        // sensitivity curve --------------------------------------------------------------
        // 先頭2行はヘッダ. 各行 wavelength, b, g, r (5列目以降は無視)
        if (!readLinesFromFd(fdSensit, lines))
            return "感度データを読めません";
        vector<array<double, 2>> sensit; // {wavelength, b+g+r}
        for (size_t i = 2; i < lines.size(); i++)
        {
            if (isBlank(lines[i]))
                continue;
            if (!parseNumbers(lines[i], nums) || nums.size() < 4)
                return "感度データの形式が不正です(" + to_string(i + 1) + "行目)";
            sensit.push_back({nums[0], nums[1] + nums[2] + nums[3]});
        }
        if (sensit.size() < 2)
            return "感度データが足りません";
        sort(sensit.begin(), sensit.end(),
             [](const array<double, 2> &a, const array<double, 2> &b) { return a[0] < b[0]; });

        // accumulate 縦 =======================
        int y1 = h / 2 - BAND_WIDTH / 2;
        int y2 = h / 2 + BAND_WIDTH / 2;
        if (y1 < 0)
            y1 = 0;
        if (y2 > h)
            y2 = h;

        vector<double> pure[3];
        for (int x = fol; x > 0; x--)
        {
            int count[3] = {0, 0, 0};
            double mean[3] = {0, 0, 0};
            double sigma[3] = {0, 0, 0};
            double sum[3] = {0, 0, 0};
            // get mean
            for (int y = y1; y < y2; y++)
            {
                int ch = bayerChannel(x, y);
                mean[ch] += (double)img.at<float>(y, x);
                count[ch]++;
            }
            for (int ch = 0; ch < 3; ch++)
                if (count[ch] > 0)
                    mean[ch] /= (double)count[ch];
            // get variance(sigma)
            for (int y = y1; y < y2; y++)
            {
                int ch = bayerChannel(x, y);
                double val = (double)img.at<float>(y, x);
                sigma[ch] += (val - mean[ch]) * (val - mean[ch]);
            }
            for (int ch = 0; ch < 3; ch++)
                if (count[ch] > 0)
                    sigma[ch] = sqrt(sigma[ch] / (double)count[ch]);
            // accumulate with sigma clipping
            for (int y = y1; y < y2; y++)
            {
                int ch = bayerChannel(x, y);
                double val = (double)img.at<float>(y, x);
                if (SIGMA_THRES * sigma[ch] < fabs(val - mean[ch]))
                {
                    count[ch]--;
                }
                else
                {
                    if (val < 0)
                        val = 0;
                    sum[ch] += val;
                }
            }
            for (int ch = 0; ch < 3; ch++)
                pure[ch].push_back(sum[ch] / (double)max(count[ch], 1));
        }
        const int size = (int)pure[0].size();

        // bとrの欠落を埋めて、minをget =======================
        double minv[3];
        fill(minv, minv + 3, numeric_limits<double>::max());
        for (int i = 1; i < size - 1; i++)
        {
            // bayer arrayにより欠落が生じるから
            for (int c : {0, 2})
            {
                if (pure[c][i] == 0)
                    pure[c][i] = (pure[c][i - 1] + pure[c][i + 1]) / 2;
            }
            if (T_MIN < i && i < T_MAX)
            {
                for (int c = 0; c < 3; c++)
                    minv[c] = min(minv[c], pure[c][i]);
            }
        }

        // wavelength,sensitivity calibration & get max =======================
        vector<double> wavelengths;
        vector<double> intensities;
        double maxv = 0;
        for (int i = T_MIN + 1; i < T_MAX && i < size; i++)
        {
            // langange interpolation | t -> t_p (cubic)
            const double t = i;
            double t_p = 0;
            for (int j = 0; j < 4; j++)
            {
                double i_nume = 1.0;
                for (int k = 0; k < 4; k++)
                {
                    if (k != j)
                        i_nume *= (t - t_ref[k]);
                }
                t_p += c_ref[j] * i_nume / i_deno[j];
            }
            if (!(400 < t_p && t_p < 700))
                continue;

            // 感度を線形補間 (表の範囲外は端の値を使う)
            auto hi = upper_bound(sensit.begin(), sensit.end(), t_p,
                                  [](double v, const array<double, 2> &e) { return v < e[0]; });
            double s;
            if (hi == sensit.begin())
            {
                s = sensit.front()[1];
            }
            else if (hi == sensit.end())
            {
                s = sensit.back()[1];
            }
            else
            {
                auto lo = hi - 1;
                s = (*lo)[1] + (t_p - (*lo)[0]) * ((*hi)[1] - (*lo)[1]) / ((*hi)[0] - (*lo)[0]);
            }
            if (!(s > 0))
                continue; // 感度0の波長は補正できない

            double bgr = 0;
            for (int c = 0; c < 3; c++)
            {
                double v = pure[c][i] - minv[c];
                if (v > 0)
                    bgr += v;
            }
            bgr /= s;
            maxv = max(maxv, bgr);
            wavelengths.push_back(t_p);
            intensities.push_back(bgr);
        }
        if (wavelengths.empty())
            return "400-700nm に入る点がありません (校正データと fol を確認してください)";
        if (!(maxv > 0))
            return "スペクトルの強度が0です";

        // export ===========================
        stringstream spectrum;
        spectrum << header << "\n";
        spectrum << "wavelength/nm,relative intensity(0.0 -- 1.0)" << "\n";
        for (size_t i = 0; i < wavelengths.size(); i++)
        {
            spectrum << wavelengths[i] << "," << intensities[i] / maxv << "\n";
        }
        const string out = spectrum.str();
        if (!writeAllToFd(fdOut, (const uchar *)out.data(), out.size()))
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
        jint fdJpeg)
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
            cvtColor(tmp, displayMat, COLOR_BayerGR2BGR);

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
        jint fol)
    {
        return runGuarded(env, [&]() { return makeCsv(fd1, fd2, fd3, fd4, fd5, fol); });
    }
}
