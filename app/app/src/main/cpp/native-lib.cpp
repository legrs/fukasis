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
#include <jni.h>
#include <jni.h>
#include <fstream>
#include <opencv2/opencv.hpp>
#include <sys/mman.h> 
#include <sys/stat.h> 
#include <unistd.h>   

#define LOG_TAG "CameraNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define WIDTH 640
#define HEIGHT 640

using namespace cv;
using namespace std;


static Mat stacked; // CV_32FC1
static int capCount;
static int width;
static int height;


extern "C"{
// FDにバイナリデータを書き込むヘルパー関数
bool writeMatToFd(int fd, const Mat& mat, const std::string& ext, const std::vector<int>& params) {
    std::vector<uchar> buffer;
    
    // 1. 指定フォーマットでエンコード (メモリ上でバイナリ化)
    // extensionは ".tif" や ".jpg"
    if (!imencode(ext, mat, buffer, params)) {
        return false;
    }

    // 2. FDに書き込み
    // write(ファイル記述子, データポインタ, サイズ)
    ssize_t written = write(fd, buffer.data(), buffer.size());
    
    // データが確実にディスクに書き込まれるように同期 (任意)
    fsync(fd);

    return (written == buffer.size());
}

JNIEXPORT void JNICALL
Java_com_example_ssa_Cam_prepare(
        JNIEnv* env, 
        jobject,
        jint img_width, 
        jint img_height
        ){

    width = img_width;
    height = img_height;
    stacked = Mat::zeros(height,width,CV_32FC1);
    capCount = 0;
}

JNIEXPORT jstring JNICALL
Java_com_example_ssa_Cam_accumulateImg(
        JNIEnv* env,
        jobject tmp,
        jobject buff,
        jint rowStride,
        jint bufferSize
        ){
    
    std::stringstream ss;
    if(buff == nullptr){
        ss << "ぬるぽ" << endl;
        return env->NewStringUTF(ss.str().c_str());
    }
    uint8_t* dataPtr = (uint8_t*)env->GetDirectBufferAddress(buff);
    if(dataPtr == nullptr){
        ss << "ぬるぽ1" << endl;
        return env->NewStringUTF(ss.str().c_str());
    }
    if(bufferSize < (height - 1)* rowStride + (width * 2)){
        ss << "サイズが小さすぎるんだよね" << endl;
        return env->NewStringUTF(ss.str().c_str());
    }

    Mat rawMat(height, width, CV_16UC1, (void*)dataPtr, rowStride);

    ss << "値ですか？えっと…" << rawMat.at<uint16_t>(0,0) << "、になって、ますけど…" << endl;
    // accumulate()がCV_16UC1非対応なので
    Mat rawMat32;
    rawMat.convertTo(rawMat32, CV_32FC1);
    rawMat.release();

    if(stacked.empty()){
        ss << "えっと…empty…なん、ですけど…" << endl;
        return env->NewStringUTF(ss.str().c_str());
    }
    if(stacked.size() != rawMat32.size()){
        ss << "サイズがちがうです…" << endl;
        return env->NewStringUTF(ss.str().c_str());
    }
    accumulate(rawMat32, stacked);
    ss << "stackedの方は…" << stacked.at<float>(0,0) << "、えす" << endl;
    capCount++;

    ss << "accumulateせいこう！です!" << endl;
    return env->NewStringUTF(ss.str().c_str());

}


//JNIEXPORT jbyteArray JNICALL
//Java_com_example_ssa_Cam_processImg(
//        JNIEnv* env,
//        jobject tmp,
//        jstring jfilepath
//        ){
//    
//    std::stringstream ss;
//    if(jfilepath == nullptr){
//        ss << "ぬるぽ" << endl;
//        return nullptr;
//    }
//
//
//    ss << "" << stacked.at<float>(10,10) << "、ですね!" << endl;
//    Mat stacked16;
//    //stacked.convertTo(stacked16, CV_32FC1, 1.0 / 65535.0 );
//    stacked.convertTo(stacked16, CV_16UC1, 16.0 / capCount);
//
//    std::vector<unsigned char> buffer;
//    if(!imencode(".tif", stacked16, buffer)){
//        return nullptr;
//    }
//
//    jbyteArray resultByte = env->NewByteArray(buffer.size());
//    env->SetByteArrayRegion(resultByte, 0, buffer.size(), (jbyte*)buffer.data());
//
//    /*
//    Mat result;
//
//    stacked.convertTo(*result, CV_16U1, 1.0 / capCount );
//    stacked.release();
//    */
//
//
//
//    //imwrite()
//
//    // save csv
//    const char* filepath = env->GetStringUTFChars(jfilepath, nullptr);
//    std::fstream csvFile(filepath, std::ios::out);
//    if(csvFile.is_open()){
//        csvFile << ss.str();
//        csvFile.close();
//    }else{
//        ss << "file is not opened" << std::endl;
//    }
//    env->ReleaseStringUTFChars(jfilepath, filepath);
//
//    return resultByte;
//}


JNIEXPORT jstring JNICALL
Java_com_example_ssa_Cam_saveImg(
        JNIEnv* env, jobject,
        jint fdTiff,
        jint fdJpeg) {

    stringstream ss;

    vector<int> tiffParams;
    tiffParams.push_back(IMWRITE_TIFF_COMPRESSION);
    tiffParams.push_back(1); // no comp

    // FDへ書き込み
    writeMatToFd(fdTiff, stacked, ".tif", tiffParams);

    Mat displayMat;
    
    // 32bit -> 8bit (0.0-1.0 を 0-255 に)
    Mat tmp;
    normalize(stacked, tmp, 0, 255, NORM_MINMAX, CV_8UC1);
    cvtColor(tmp, displayMat, COLOR_BayerGR2BGR);

    ss << "stackedの値は、" << stacked.at<float>(0,0) << "、ですよ！" << endl;
    ss << "tmpの値は、" << to_string(tmp.at<unsigned char>(0,0)) << "、ですよ！" << endl;
    ss << "displayMatの値は…" << to_string(displayMat.at<Vec3b>(0,0)[0]) << "、ですよ…………疲れました" << endl;

    vector<int> jpgParams;
    jpgParams.push_back(IMWRITE_JPEG_QUALITY);
    jpgParams.push_back(90);

    // FDへ書き込み
    writeMatToFd(fdJpeg, displayMat, ".jpg", jpgParams);

    return env->NewStringUTF(ss.str().c_str());
}
JNIEXPORT jstring JNICALL
Java_com_example_ssa_DarkActivity_processImgs(
        JNIEnv* env, jobject,
        jint fd1,
        jint fd2,
        jint fd3
        ) {

    stringstream ss;
    ss << " " ;

    // get file size
    struct stat sb;
    if (fstat(fd1, &sb) == -1) return env->NewStringUTF("fd1 error");
    size_t fileSize = sb.st_size;

    // ファイルをメモリ空間に投影
    void* mapAddr = mmap(NULL, fileSize, PROT_READ, MAP_PRIVATE, fd1, 0);
    if (mapAddr == MAP_FAILED) return env->NewStringUTF("fd1 map failed");
    Mat rawDatMat(1, fileSize, CV_32FC1, mapAddr);
    Mat lightMat = imdecode(rawDatMat, IMREAD_UNCHANGED);
    // release
    munmap(mapAddr, fileSize);

    if (fstat(fd2, &sb) == -1) return env->NewStringUTF("fd2 error");
    fileSize = sb.st_size;

    mapAddr = mmap(NULL, fileSize, PROT_READ, MAP_PRIVATE, fd2, 0);
    if (mapAddr == MAP_FAILED) return env->NewStringUTF("fd2 map failed");
    rawDatMat = Mat(1, fileSize, CV_32FC1, mapAddr);
    Mat darkMat = imdecode(rawDatMat, IMREAD_UNCHANGED);
    // release
    munmap(mapAddr, fileSize);

    if(!lightMat.empty() && !darkMat.empty()){


        Mat result = lightMat - darkMat;
        //Mat result16;
        //result.convertTo(result16, CV_16UC1);

        vector<int> tiffParams;
        tiffParams.push_back(IMWRITE_TIFF_COMPRESSION);
        tiffParams.push_back(1); // no comp

        // FDへ書き込み
        writeMatToFd(fd3, result, ".tif", tiffParams);



    }else{
        ss << "emptyなんです" << endl;
        LOGE("fd1 error");
    }

    return env->NewStringUTF(ss.str().c_str());

}

}
