// SPDX-License-Identifier: MIT
// native-lib.cpp を host (Linux + OpenCV) でビルドし, makecsv / processImgs / saveImg を
// 合成データで検証する. CI の native-test ジョブから実行される.
//
//   g++ -std=c++17 -I stubs $(pkg-config --cflags opencv4) native_lib_test.cpp $(pkg-config --libs opencv4)
#include "../../main/cpp/native-lib.cpp"

#include <cstdio>
#include <fcntl.h>
#include <functional>

static int failures = 0;

#define CHECK(cond)                                                                   \
    do                                                                                \
    {                                                                                 \
        if (!(cond))                                                                  \
        {                                                                             \
            fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            failures++;                                                               \
        }                                                                             \
    } while (0)

static string tmpDir;

static string pathOf(const string &name)
{
    return tmpDir + "/" + name;
}

static void writeFile(const string &name, const string &content)
{
    FILE *f = fopen(pathOf(name).c_str(), "wb");
    fwrite(content.data(), 1, content.size(), f);
    fclose(f);
}

static void writeImage(const string &name, const Mat &m)
{
    vector<uchar> buf;
    imencode(".tif", m, buf, tiffParams());
    writeFile(name, string(buf.begin(), buf.end()));
}

static string readFile(const string &name)
{
    FILE *f = fopen(pathOf(name).c_str(), "rb");
    if (!f)
        return "";
    string s;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0)
        s.append(buf, n);
    fclose(f);
    return s;
}

static Mat readImage(const string &name)
{
    string s = readFile(name);
    return imdecode(Mat(1, (int)s.size(), CV_8UC1, (void *)s.data()), IMREAD_UNCHANGED);
}

static int openRead(const string &name)
{
    return open(pathOf(name).c_str(), O_RDONLY);
}

static int openWrite(const string &name)
{
    return open(pathOf(name).c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
}

// ---- 合成データ ----------------------------------------------------------

const int W = 3300;
const int H = 200;
const int FOL = 3200;
// fol からの距離 i -> 波長 λ = 430 + (i - 1900) * 0.3 (線形分散)
const string CALIB = "1900,2100,2300,2500\n430.000000,490.000000,550.000000,610.000000";
const string META = "test, 2026-10-01T00:00:00Z,  ISO 3200, fd 1.000000, 100 msec * 1 ";

static double wavelengthAt(int i)
{
    return 430 + (i - 1900) * 0.3;
}

// value(i) で各列の値を決めた画像 (i = FOL - x)
static Mat makeImage(const function<double(int)> &value)
{
    Mat m(H, W, CV_32FC1);
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++)
            m.at<float>(y, x) = (float)value(FOL - x);
    return m;
}

// 先頭2行ヘッダ + "λ,b,g,r" の感度データ. sum(λ) が b+g+r になる
static string makeSensit(int from, int to, int step, const function<double(double)> &sum)
{
    stringstream ss;
    ss << "sensitivity\nwavelength,b,g,r\n";
    for (int l = from; l <= to; l += step)
    {
        double s = sum(l);
        ss << l << "," << s * 0.2 << "," << s * 0.3 << "," << s * 0.5 << "\n";
    }
    return ss.str();
}

struct Row
{
    double wl;
    double val;
};

static bool parseSpectrum(const string &text, vector<string> &head, vector<Row> &rows)
{
    stringstream ss(text);
    string line;
    head.clear();
    rows.clear();
    for (int i = 0; i < 2 && getline(ss, line); i++)
        head.push_back(line);
    vector<double> v;
    while (getline(ss, line))
    {
        if (!parseNumbers(line, v) || v.size() != 2)
            return false;
        rows.push_back({v[0], v[1]});
    }
    return head.size() == 2;
}

static string callMakecsv(const string &img, const string &calib, const string &meta, const string &sensit,
                          const string &out, int fol)
{
    int fd1 = openRead(img), fd2 = openRead(calib), fd3 = openRead(meta), fd4 = openRead(sensit), fd5 = openWrite(out);
    JNIEnv env;
    jstring r = Java_com_example_ssa_CsvActivity_makecsv(&env, nullptr, fd1, fd2, fd3, fd4, fd5, fol);
    for (int fd : {fd1, fd2, fd3, fd4, fd5})
        close(fd);
    return r->str;
}

static const Row &nearestRow(const vector<Row> &rows, double wl)
{
    size_t best = 0;
    for (size_t i = 1; i < rows.size(); i++)
        if (fabs(rows[i].wl - wl) < fabs(rows[best].wl - wl))
            best = i;
    return rows[best];
}

// ---- tests -------------------------------------------------------------

static void testParseNumbers()
{
    vector<double> v;
    CHECK(parseNumbers("1, 2.5,3e2", v) && v.size() == 3 && v[2] == 300);
    CHECK(!parseNumbers("1,abc", v));
    CHECK(!parseNumbers("", v));
    CHECK(!parseNumbers("1,,2", v));
}

static void testMakecsvPeak()
{
    // 背景 10 + 距離 2200 (520nm) に輝線
    writeImage("peak.tif", makeImage([](int i)
                                     { return 10 + 1000 * exp(-pow(i - 2200, 2) / (2 * 9.0)); }));
    writeFile("calib.csv", CALIB);
    writeFile("meta.csv", META);
    // 末尾の空行や CRLF があっても読めること
    string sensit = makeSensit(350, 800, 10, [](double)
                               { return 1.0; });
    string crlf;
    for (char c : sensit)
        crlf += (c == '\n') ? string("\r\n") : string(1, c);
    writeFile("sensit.csv", crlf + "\r\n\r\n");

    string err = callMakecsv("peak.tif", "calib.csv", "meta.csv", "sensit.csv", "peak.csv", FOL);
    CHECK(err.empty());
    if (!err.empty())
        fprintf(stderr, "makecsv: %s\n", err.c_str());

    vector<string> head;
    vector<Row> rows;
    CHECK(parseSpectrum(readFile("peak.csv"), head, rows));
    CHECK(head.size() == 2 && head[0] == META);
    CHECK(head.size() == 2 && head[1] == "wavelength/nm,relative intensity(0.0 -- 1.0)");
    CHECK(rows.size() == 999); // 1800 < i < 2800

    const Row *peak = nullptr;
    bool increasing = true;
    for (size_t k = 0; k < rows.size(); k++)
    {
        CHECK(rows[k].wl > 400 && rows[k].wl < 700);
        CHECK(rows[k].val >= 0 && rows[k].val <= 1.0 + 1e-9);
        if (k > 0 && rows[k].wl <= rows[k - 1].wl)
            increasing = false;
        if (!peak || rows[k].val > peak->val)
            peak = &rows[k];
    }
    CHECK(increasing);
    CHECK(peak && fabs(peak->val - 1.0) < 1e-6); // 最大値で正規化されている
    CHECK(peak && fabs(peak->wl - 520.0) < 0.5);
}

static void testMakecsvSensitivityInterpolation()
{
    // 強度が距離に比例するランプ. 感度は 50nm 刻みの粗い表で sum(λ) = λ/100 (線形なので補間は厳密に一致するはず)
    writeImage("ramp.tif", makeImage([](int i)
                                     { return max(0, i - 1800); }));
    writeFile("calib.csv", CALIB);
    writeFile("meta.csv", META);
    writeFile("coarse.csv", makeSensit(350, 800, 50, [](double l)
                                       { return l / 100.0; }));

    string err = callMakecsv("ramp.tif", "calib.csv", "meta.csv", "coarse.csv", "ramp.csv", FOL);
    CHECK(err.empty());

    vector<string> head;
    vector<Row> rows;
    CHECK(parseSpectrum(readFile("ramp.csv"), head, rows));
    if (rows.empty())
        return;
    // min(=i 1801 の値 1) を引いた後 3 チャネル分を感度で割る: (i - 1801) * 3 / (λ/100)
    auto expected = [](int i)
    { return 3.0 * (i - 1801) / (wavelengthAt(i) / 100.0); };
    const Row &a = nearestRow(rows, wavelengthAt(2000));
    const Row &b = nearestRow(rows, wavelengthAt(2600));
    double ratio = a.val / b.val;
    double expectedRatio = expected(2000) / expected(2600);
    CHECK(fabs(ratio / expectedRatio - 1.0) < 1e-3);
}

static void testMakecsvErrors()
{
    writeImage("ramp.tif", makeImage([](int i)
                                     { return max(0, i - 1800); }));
    writeFile("meta.csv", META);
    writeFile("sensit.csv", makeSensit(350, 800, 10, [](double)
                                       { return 1.0; }));

    // 画素位置が重複した校正データ (以前は 0 除算 -> 範囲外アクセスでクラッシュしていた)
    writeFile("dup.csv", "1900,1900,2300,2500\n430,490,550,610");
    CHECK(!callMakecsv("ramp.tif", "dup.csv", "meta.csv", "sensit.csv", "out.csv", FOL).empty());

    // 1行しかない校正データ
    writeFile("short.csv", "1900,2100,2300,2500\n");
    CHECK(!callMakecsv("ramp.tif", "short.csv", "meta.csv", "sensit.csv", "out.csv", FOL).empty());

    writeFile("calib.csv", CALIB);
    // fol が画像の外 / 0 (スライダー未操作)
    CHECK(!callMakecsv("ramp.tif", "calib.csv", "meta.csv", "sensit.csv", "out.csv", W).empty());
    CHECK(!callMakecsv("ramp.tif", "calib.csv", "meta.csv", "sensit.csv", "out.csv", 0).empty());

    // 空の画像 (以前は空の darked.tif を読んでクラッシュしていた)
    writeFile("empty.tif", "");
    CHECK(!callMakecsv("empty.tif", "calib.csv", "meta.csv", "sensit.csv", "out.csv", FOL).empty());

    // 数値でない感度データ, 列の多すぎる感度データ (以前は stof の例外で abort / 配列外書き込み)
    writeFile("bad.csv", "h\nh\n400,1,1,1\n410,x,1,1\n");
    CHECK(!callMakecsv("ramp.tif", "calib.csv", "meta.csv", "bad.csv", "out.csv", FOL).empty());
    writeFile("wide.csv", "h\nh\n350,1,1,1,9,9,9\n800,1,1,1,9,9,9\n");
    CHECK(callMakecsv("ramp.tif", "calib.csv", "meta.csv", "wide.csv", "out.csv", FOL).empty());

    // 空のメタデータ
    writeFile("nometa.csv", "");
    CHECK(!callMakecsv("ramp.tif", "calib.csv", "nometa.csv", "sensit.csv", "out.csv", FOL).empty());
}

static void testProcessImgs()
{
    writeImage("light.tif", makeImage([](int i)
                                      { return 100 + (i % 7); }));
    writeImage("dark.tif", makeImage([](int)
                                     { return 5; }));
    int fd1 = openRead("light.tif"), fd2 = openRead("dark.tif"), fd3 = openWrite("darked.tif");
    JNIEnv env;
    string err = Java_com_example_ssa_DarkActivity_processImgs(&env, nullptr, fd1, fd2, fd3)->str;
    close(fd1);
    close(fd2);
    close(fd3);
    CHECK(err.empty());
    Mat out = readImage("darked.tif");
    CHECK(!out.empty() && out.type() == CV_32FC1 && out.size() == Size(W, H));
    if (!out.empty())
        CHECK(fabs(out.at<float>(10, 123) - (100 + ((FOL - 123) % 7) - 5)) < 1e-4);

    // サイズ違い
    writeImage("small.tif", Mat(10, 10, CV_32FC1, Scalar(1)));
    fd1 = openRead("light.tif"), fd2 = openRead("small.tif"), fd3 = openWrite("darked2.tif");
    err = Java_com_example_ssa_DarkActivity_processImgs(&env, nullptr, fd1, fd2, fd3)->str;
    close(fd1);
    close(fd2);
    close(fd3);
    CHECK(!err.empty());
}

static void testStackAndSave()
{
    JNIEnv env;
    const int w = 4, h = 4;
    Java_com_example_ssa_Cam_prepare(&env, nullptr, w, h);

    // 積算前に保存しようとしたらエラー
    int fdT = openWrite("s.tif"), fdJ = openWrite("s.jpg");
    CHECK(!Java_com_example_ssa_Cam_saveImg(&env, nullptr, fdT, fdJ)->str.empty());
    close(fdT);
    close(fdJ);

    for (uint16_t value : {100, 300})
    {
        vector<uint16_t> raw(w * h, value);
        _jobject buf;
        buf.buffer = raw.data();
        CHECK(Java_com_example_ssa_Cam_accumulateImg(&env, nullptr, &buf, w * 2, (jint)(raw.size() * 2))->str.empty());
    }
    // バッファが小さすぎる場合は積算しない
    vector<uint16_t> tooSmall(2, 1);
    _jobject small;
    small.buffer = tooSmall.data();
    CHECK(!Java_com_example_ssa_Cam_accumulateImg(&env, nullptr, &small, w * 2, 4)->str.empty());

    fdT = openWrite("s.tif");
    fdJ = openWrite("s.jpg");
    CHECK(Java_com_example_ssa_Cam_saveImg(&env, nullptr, fdT, fdJ)->str.empty());
    close(fdT);
    close(fdJ);
    Mat tif = readImage("s.tif");
    CHECK(!tif.empty() && tif.type() == CV_32FC1);
    if (!tif.empty())
        CHECK(fabs(tif.at<float>(1, 1) - 200.0f) < 1e-4); // 合計(400)ではなく平均
    CHECK(!readImage("s.jpg").empty());
}

int main()
{
    char tmpl[] = "/tmp/native_lib_test_XXXXXX";
    tmpDir = mkdtemp(tmpl);

    testParseNumbers();
    testMakecsvPeak();
    testMakecsvSensitivityInterpolation();
    testMakecsvErrors();
    testProcessImgs();
    testStackAndSave();

    if (failures)
    {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("all native tests passed\n");
    return 0;
}
