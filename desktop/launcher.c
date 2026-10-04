/*
 * Local Dream ET - Windows desktop launcher  (v3.0.1)
 * Developer (开发者): ET   Copyright (C) 2026 ET
 *
 * Pure Win32 C front-end around the official stable-diffusion.cpp engine
 * (sd-cli.exe + ggml DLLs, Copyright (c) 2023 leejet, MIT) shipped next to the
 * exe. Phone-like narrow vertical UI: top CPU/NPU tabs + search, model cards,
 * tap a downloaded model to enter the generation page, settings page. Fully
 * offline local SD1.5 generation. No Python needed at runtime.
 *
 * The model catalog and trilingual UI strings are AUTO-GENERATED into catalog.h
 * from models.json by tools/gen_catalog.py (build time only).
 *
 * Build:
 *   x86_64-w64-mingw32-gcc -O2 -municode -mwindows launcher.c resource.o \
 *     -o LocalDream-ET.exe -lcomctl32 -lshlwapi -lwininet -lole32 -loleaut32 \
 *     -lgdi32 -luser32 -lshell32 -lcomdlg32 -luuid -lws2_32 -static-libgcc
 */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define _WIN32_WINNT 0x0601
#define CINTERFACE
#define COBJMACROS

#include <windows.h>
#include <commctrl.h>
#include <commdlg.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <wininet.h>
#include <olectl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wchar.h>

#include "catalog.h"

#define UPDATE_MANIFEST L"https://etqwfd.github.io/LocalDream-ET/desktop-update.json"

/* =============================== i18n / theme ========================== */
/* g_lang: 0=简体中文, 1=English, 2=繁體中文 */
static int g_lang = 0;
static int g_dark = 1;
#define T(k) g_ui[g_lang][UIDX_##k]

static COLORREF g_cBg, g_cPanel, g_cCard, g_cText, g_cSub, g_cAccent;
static HBRUSH g_brBg, g_brPanel, g_brCard;

static void setupColors(void)
{
    if (g_brBg) DeleteObject(g_brBg);
    if (g_brPanel) DeleteObject(g_brPanel);
    if (g_brCard) DeleteObject(g_brCard);
    if (g_dark) {
        g_cBg = RGB(24, 24, 28); g_cPanel = RGB(32, 32, 38); g_cCard = RGB(44, 44, 52);
        g_cText = RGB(236, 232, 230); g_cSub = RGB(158, 154, 158); g_cAccent = RGB(244, 150, 130);
    } else {
        g_cBg = RGB(240, 241, 245); g_cPanel = RGB(255, 255, 255); g_cCard = RGB(255, 255, 255);
        g_cText = RGB(28, 28, 34); g_cSub = RGB(110, 110, 118); g_cAccent = RGB(210, 96, 78);
    }
    g_brBg = CreateSolidBrush(g_cBg);
    g_brPanel = CreateSolidBrush(g_cPanel);
    g_brCard = CreateSolidBrush(g_cCard);
}

/* UCRT-safe strtok wrapper (3-arg). */
static wchar_t *mywcstok(wchar_t *s, const wchar_t *delim, wchar_t **ctx)
{
    wchar_t *beg;
    if (s) *ctx = s;
    if (!*ctx || !**ctx) return NULL;
    while (**ctx && wcschr(delim, **ctx)) (*ctx)++;
    if (!**ctx) return NULL;
    beg = *ctx;
    while (**ctx) {
        if (wcschr(delim, **ctx)) { **ctx = 0; (*ctx)++; return beg; }
        (*ctx)++;
    }
    return beg;
}

/* ============================ offline ZH -> EN tags ===================== */
typedef struct { const wchar_t *zh, *en; } ZE;
static const ZE g_ze[] = {
    { L"最高画质", L"best quality, ultra detailed" }, { L"照片级", L"photorealistic" },
    { L"高质量", L"high quality, highly detailed" }, { L"杰作", L"masterpiece" },
    { L"高清", L"high resolution, sharp focus" }, { L"超高清", L"ultra high resolution, 8k" },
    { L"电影感", L"cinematic, cinematic lighting" }, { L"电影级", L"cinematic shot, dramatic lighting" },
    { L"写实", L"photorealistic, realistic" }, { L"真实", L"realistic, lifelike" },
    { L"唯美", L"aesthetic, beautiful" }, { L"精美", L"exquisite, beautiful detailed" },
    { L"二次元", L"anime style, 2d" }, { L"动漫", L"anime style" }, { L"动画", L"anime style" },
    { L"卡通", L"cartoon style" }, { L"赛博朋克", L"cyberpunk" },
    { L"古风", L"chinese ancient style" }, { L"国风", L"chinese style" },
    { L"水墨画", L"chinese ink painting" }, { L"油画", L"oil painting" }, { L"水彩", L"watercolor" },
    { L"素描", L"pencil sketch" }, { L"像素风", L"pixel art" }, { L"概念艺术", L"concept art" },
    { L"奇幻", L"fantasy art" }, { L"科幻", L"sci-fi" }, { L"特写", L"close-up shot" },
    { L"近景", L"close-up" }, { L"全景", L"wide shot, panoramic" }, { L"广角", L"wide angle lens" },
    { L"仰拍", L"low angle shot" }, { L"俯拍", L"high angle shot" }, { L"鸟瞰", L"bird's eye view" },
    { L"虚化", L"bokeh, depth of field" }, { L"景深", L"depth of field" }, { L"逆光", L"backlighting" },
    { L"夜景", L"night scene, night" }, { L"黄昏", L"sunset, dusk" }, { L"日出", L"sunrise" },
    { L"蓝天", L"blue sky" }, { L"白云", L"white clouds" }, { L"女孩", L"1girl" },
    { L"少女", L"1girl, young girl" }, { L"男孩", L"1boy" }, { L"少年", L"1boy" },
    { L"女人", L"1woman" }, { L"男人", L"1man" }, { L"美女", L"beautiful woman, pretty face" },
    { L"帅哥", L"handsome man" }, { L"小孩", L"child" }, { L"儿童", L"child" }, { L"婴儿", L"baby" },
    { L"长发", L"long hair" }, { L"短发", L"short hair" }, { L"双马尾", L"twintails" },
    { L"马尾", L"ponytail" }, { L"白发", L"white hair" }, { L"金发", L"blonde hair" },
    { L"黑发", L"black hair" }, { L"蓝发", L"blue hair" }, { L"红眼", L"red eyes" },
    { L"蓝眼", L"blue eyes" }, { L"绿眼", L"green eyes" }, { L"微笑", L"smile" },
    { L"笑容", L"smiling" }, { L"可爱", L"cute, kawaii" }, { L"漂亮", L"beautiful" },
    { L"帅气", L"handsome" }, { L"连衣裙", L"dress" }, { L"裙子", L"skirt" },
    { L"校服", L"school uniform" }, { L"制服", L"uniform" }, { L"西装", L"suit" },
    { L"和服", L"kimono" }, { L"汉服", L"hanfu" }, { L"卫衣", L"hoodie" },
    { L"泳衣", L"swimsuit" }, { L"帽子", L"hat" }, { L"眼镜", L"glasses" },
    { L"猫耳", L"cat ears" }, { L"兔耳", L"bunny ears" }, { L"翅膀", L"wings" },
    { L"天使", L"angel" }, { L"恶魔", L"demon" }, { L"精灵", L"elf" },
    { L"公主", L"princess" }, { L"骑士", L"knight" }, { L"武士", L"samurai" },
    { L"忍者", L"ninja" }, { L"机甲", L"mecha" }, { L"猫咪", L"cat" }, { L"小猫", L"kitten" },
    { L"小狗", L"puppy" }, { L"兔子", L"rabbit" }, { L"狐狸", L"fox" }, { L"樱花", L"cherry blossom" },
    { L"玫瑰", L"rose" }, { L"森林", L"forest" }, { L"山脉", L"mountains" }, { L"湖泊", L"lake" },
    { L"河流", L"river" }, { L"大海", L"sea, ocean" }, { L"海边", L"seaside, beach" },
    { L"海滩", L"beach" }, { L"沙滩", L"sandy beach" }, { L"瀑布", L"waterfall" },
    { L"草原", L"grassland, meadow" }, { L"花田", L"flower field" }, { L"天空", L"sky" },
    { L"星空", L"starry sky" }, { L"星星", L"stars" }, { L"月亮", L"moon" }, { L"太阳", L"sun" },
    { L"城市", L"city" }, { L"街道", L"street" }, { L"建筑", L"building" }, { L"高楼", L"skyscraper" },
    { L"城堡", L"castle" }, { L"房子", L"house" }, { L"房间", L"room" }, { L"室内", L"indoors" },
    { L"户外", L"outdoors" }, { L"公园", L"park" }, { L"花园", L"garden" }, { L"寺庙", L"temple" },
    { L"宇宙", L"space, universe" }, { L"星球", L"planet" }, { L"飞船", L"spaceship" },
    { L"跑车", L"sports car" }, { L"汽车", L"car" }, { L"摩托车", L"motorcycle" },
    { L"魔法", L"magic" }, { L"发光", L"glowing" }, { L"水晶", L"crystal" },
    { L"霓虹灯", L"neon lights" }, { L"灯光", L"lights" }, { L"蛋糕", L"cake" },
    { L"咖啡", L"coffee" }, { L"红色", L"red" }, { L"蓝色", L"blue" }, { L"绿色", L"green" },
    { L"黄色", L"yellow" }, { L"白色", L"white" }, { L"黑色", L"black" }, { L"粉色", L"pink" },
    { L"紫色", L"purple" }, { L"金色", L"golden" }, { L"银色", L"silver" },
    { L"站在", L"standing" }, { L"坐在", L"sitting" }, { L"奔跑", L"running" },
    { L"拿着", L"holding" }, { L"背景", L"background" }, { L"猫", L"cat" }, { L"狗", L"dog" },
    { L"鸟", L"bird" }, { L"鱼", L"fish" }, { L"马", L"horse" }, { L"狼", L"wolf" },
    { L"龙", L"dragon" }, { L"老虎", L"tiger" }, { L"狮子", L"lion" }, { L"熊猫", L"panda" },
    { L"蝴蝶", L"butterfly" }, { L"花", L"flower" }, { L"树", L"tree" }, { L"山", L"mountain" },
    { L"湖", L"lake" }, { L"河", L"river" }, { L"海", L"ocean" }, { L"云", L"clouds" },
    { L"雪", L"snow" }, { L"雨", L"rain" }, { L"桥", L"bridge" }, { L"剑", L"sword" },
    { L"刀", L"blade" }, { L"枪", L"gun" },
};
#define ZE_COUNT (int)(sizeof(g_ze) / sizeof(g_ze[0]))

static int hasCjk(const wchar_t *s)
{
    if (!s) return 0;
    for (; *s; s++) if (*s >= 0x4E00 && *s <= 0x9FFF) return 1;
    return 0;
}

static void sentenceToTags(const wchar_t *in, wchar_t *out, size_t outc)
{
    static wchar_t buf[6000];
    _snwprintf(buf, 6000, L"%ls", in);
    _wcslwr(buf);
    for (wchar_t *p = buf; *p; p++) {
        wchar_t c = *p;
        int ok = (c >= L'a' && c <= L'z') || (c >= L'0' && c <= L'9') || c == L',' || c == L' ';
        if (!ok) *p = L' ';
    }
    static const wchar_t *stops[] = {
        L"there is", L"there are", L" is standing", L" is sitting", L" is holding",
        L" who ", L" which ", L" that ", L" wearing ", L" inside ",
        L" in front of ", L" in the ", L" in a ", L" in ", L" on the ", L" on a ",
        L" under the ", L" under ", L" over ", L" next to ", L" near ", L" behind ",
        L" beside ", L" between ", L" while ", L" when ", L" then ", L" and ",
        L" with a ", L" with ", L" of a ", L" of the ", L" of ", L" the ",
        L" this ", L" that ", L" is ", L" are ", L" am ", L" be ", L" being ",
        L" to ", L" at ", L" by ", L" as ", NULL
    };
    for (int i = 0; stops[i]; i++) {
        wchar_t *p;
        while ((p = wcsstr(buf, stops[i])) != NULL) {
            size_t n = wcslen(stops[i]);
            memmove(p + 2, p + n, (wcslen(p + n) + 1) * sizeof(wchar_t));
            p[0] = L','; p[1] = L' ';
        }
    }
    wchar_t res[6000]; res[0] = 0;
    wchar_t copy[6000]; _snwprintf(copy, 6000, L"%ls", buf);
    wchar_t *ctx = NULL;
    wchar_t *tok = mywcstok(copy, L" ,", &ctx);
    while (tok) {
        if (wcslen(tok) >= 2) {
            int seen = 0;
            for (wchar_t *q = res; ; ) { wchar_t *c = wcschr(q, L','); size_t L = c ? (size_t)(c - q) : wcslen(q);
                if (wcslen(tok) == L && _wcsnicmp(q, tok, L) == 0) { seen = 1; break; }
                if (!c) break; q = c + 1;
            }
            if (!seen && wcslen(res) + wcslen(tok) + 4 < 5900) {
                if (res[0]) wcscat(res, L", ");
                wcscat(res, tok);
            }
        }
        tok = mywcstok(NULL, L" ,", &ctx);
    }
    _snwprintf(out, outc, res[0] ? L"%ls, masterpiece, best quality"
                                  : L"masterpiece, best quality", res);
}

static int translateOnline(const wchar_t *zh, wchar_t *out, size_t outc)
{
    int n = WideCharToMultiByte(CP_UTF8, 0, zh, -1, NULL, 0, NULL, NULL);
    if (n <= 0 || n > 2000) return 0;
    char *u8 = (char *)malloc(n);
    if (!u8) return 0;
    WideCharToMultiByte(CP_UTF8, 0, zh, -1, u8, n, NULL, NULL);
    char *enc = (char *)malloc(n * 3 + 32);
    if (!enc) { free(u8); return 0; }
    InternetCanonicalizeUrlA(u8, enc, &(DWORD){n * 3 + 32}, ICU_ENCODE_PERCENT);
    char body[6200]; int bl = _snprintf(body, sizeof(body), "q=%s&from=zh-CHS&to=en", enc);
    free(enc); free(u8);

    HINTERNET hN = InternetOpenA("LocalDreamET/3.0", INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
    if (!hN) return 0;
    InternetSetOptionA(hN, INTERNET_OPTION_CONNECT_TIMEOUT, &(DWORD){8000}, sizeof(DWORD));
    InternetSetOptionA(hN, INTERNET_OPTION_RECEIVE_TIMEOUT, &(DWORD){8000}, sizeof(DWORD));
    HINTERNET hC = InternetConnectA(hN, "aidemo.youdao.com", INTERNET_DEFAULT_HTTPS_PORT,
                                    NULL, NULL, INTERNET_SERVICE_HTTP, 0, 0);
    if (!hC) { InternetCloseHandle(hN); return 0; }
    HINTERNET hR = HttpOpenRequestA(hC, "POST", "/trans", NULL, NULL, NULL,
                                    INTERNET_FLAG_SECURE | INTERNET_FLAG_NO_CACHE_WRITE |
                                    INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_UI, 0);
    int ok = 0;
    if (hR) {
        HttpAddRequestHeadersA(hR,
            "Content-Type: application/x-www-form-urlencoded\r\nUser-Agent: Mozilla/5.0\r\n",
            (DWORD)-1L, HTTP_ADDREQ_FLAG_ADD);
        if (HttpSendRequestA(hR, NULL, 0, body, bl)) {
            char rb[8192]; DWORD got = 0, tot = sizeof(rb) - 1;
            if (InternetReadFile(hR, rb, tot, &got) && got > 0) {
                rb[got] = 0;
                char *p = strstr(rb, "\"translation\"");
                if (p) { p = strchr(p, '['); if (p) p = strchr(p, '"'); }
                if (p) {
                    p++;
                    char en[2048]; int j = 0;
                    for (char *q = p; *q && *q != '"' && j < 2040; q++) {
                        if (*q == '\\' && (q[1] == '"' || q[1] == '\\')) continue;
                        en[j++] = *q;
                    }
                    en[j] = 0;
                    if (j > 0) {
                        wchar_t wen[2048];
                        MultiByteToWideChar(CP_UTF8, 0, en, -1, wen, 2048);
                        sentenceToTags(wen, out, outc);
                        ok = out[0] != 0;
                    }
                }
            }
        }
        InternetCloseHandle(hR);
    }
    InternetCloseHandle(hC); InternetCloseHandle(hN);
    return ok;
}

static void translatePrompt(const wchar_t *in, wchar_t *out, size_t outc)
{
    if (!hasCjk(in)) { _snwprintf(out, outc, L"%ls", in); return; }
    static wchar_t onl[6000];
    if (translateOnline(in, onl, 6000)) { _snwprintf(out, outc, L"%ls", onl); return; }

    static wchar_t work[6000];
    _snwprintf(work, 6000, L"%ls", in);
    for (int i = 0; i < ZE_COUNT; i++) {
        wchar_t *p;
        size_t zl = wcslen(g_ze[i].zh);
        while ((p = wcsstr(work, g_ze[i].zh)) != NULL) {
            wchar_t tail[4000];
            _snwprintf(tail, 4000, L"%ls", p + zl);
            _snwprintf(p, 6000 - (size_t)(p - work), L"%ls %ls", g_ze[i].en, tail);
        }
    }
    wchar_t res[6000]; res[0] = 0;
    const wchar_t *sep = L" ，,。.；;、！!？?（）()【】[]\"'“”‘’/\\";
    wchar_t copy[6000]; _snwprintf(copy, 6000, L"%ls", work);
    wchar_t *ctx = NULL;
    wchar_t *tok = mywcstok(copy, sep, &ctx);
    while (tok) {
        if (!hasCjk(tok)) {
            if (res[0] && wcslen(res) + wcslen(tok) + 4 < 5900) { wcscat(res, L", "); wcscat(res, tok); }
            else if (!res[0]) _snwprintf(res, 6000, L"%ls", tok);
        }
        tok = mywcstok(NULL, sep, &ctx);
    }
    if (!res[0]) _snwprintf(out, outc, L"masterpiece, best quality");
    else _snwprintf(out, outc, L"%ls, masterpiece, best quality", res);
}

/* ================================ state / dirs ========================== */
static HINSTANCE g_hInst;
static HWND g_hMain;
static wchar_t g_exeDir[MAX_PATH];
static wchar_t g_baseDir[MAX_PATH];
static wchar_t g_lastImage[MAX_PATH] = {0};
static wchar_t g_initImg[MAX_PATH] = {0};

static volatile LONG g_busy = 0;
static int g_sel = 0;
static int g_view = 0;          /* 0 list, 1 run, 2 settings */
static int g_tab = 0;           /* 0 CPU, 1 NPU */
static int g_mpct[MODEL_COUNT];
static int g_mstate[MODEL_COUNT];
static wchar_t g_pinned[2048] = {0};

static volatile LONG g_genRunning = 0;
static int g_genSteps = 20, g_genInterval = 1;
static wchar_t g_livePath[MAX_PATH] = {0};

#define WM_JOB_STATUS    (WM_APP + 1)
#define WM_JOB_PROGRESS  (WM_APP + 2)
#define WM_JOB_DONE      (WM_APP + 3)
#define WM_REFRESH_LIST  (WM_APP + 4)
#define WM_LIVE_PREVIEW  (WM_APP + 5)

#define IDC_TABCPU   2001
#define IDC_TABNPU   2002
#define IDC_SEARCH   2003
#define IDC_LIST     2004
#define IDC_GEAR     2005
#define IDC_LSTATUS  2006
#define IDC_BACK     2007
#define IDC_RTITLE   2008
#define IDC_PROMPT   2009
#define IDC_NEG      2010
#define IDC_STEPS    2011
#define IDC_CFG      2012
#define IDC_ASPECT   2013
#define IDC_W        2014
#define IDC_H        2015
#define IDC_SAMPLER  2016
#define IDC_SEED     2017
#define IDC_DENOISE  2018
#define IDC_COUNT    2019
#define IDC_UPLOAD   2020
#define IDC_CLEARIMG 2021
#define IDC_THUMB    2022
#define IDC_IMGNAME  2023
#define IDC_GEN      2024
#define IDC_PROGRESS 2025
#define IDC_RESULT   2026
#define IDC_SAVE     2027
#define IDC_OPNOUT   2028
#define IDC_REGEN    2029
#define IDC_RSTATUS  2030
#define IDC_SBACK    2031
#define IDC_LANG0    2032
#define IDC_LANG1    2033
#define IDC_LANG2    2034
#define IDC_THEME0   2035
#define IDC_THEME1   2036
#define IDC_CHECKUPD 2037
#define IDC_CLEANTMP 2038
#define IDC_OPMODELS 2039
#define IDC_OPOUT    2040
#define IDC_ABOUT    2041
#define IDC_MIRROR0  2050
#define IDC_MIRROR1  2051
#define IDC_MIRROR2  2052
#define IDC_DEF_NEG  2053
#define IDC_DEF_STEPS 2054
#define IDC_DEF_CFG  2055
#define IDC_DEF_COUNT 2056
#define IDC_DEF_DENOISE 2057
#define IDC_DEF_SAMPLER 2058

static HWND g_hTabCpu, g_hTabNpu, g_hSearch, g_hList, g_hGear, g_hLStatus;
static HWND g_hPan[3];
static HWND g_hBack, g_hRTitle, g_hPrompt, g_hNeg, g_hSteps, g_hCfg, g_hAspect,
            g_hW, g_hH, g_hSampler, g_hSeed, g_hDenoise, g_hCount, g_hUpload,
            g_hClearImg, g_hThumb, g_hImgName, g_hGen, g_hProgress, g_hResult,
            g_hSave, g_hOpnout, g_hRegen, g_hRStatus;
static HWND g_hSback, g_hLang[3], g_hTheme[2], g_hChkUpd, g_hCleanTmp,
            g_hOpModels, g_hAbout;
static HWND g_hMirror[3], g_hDefNeg, g_hDefSteps, g_hDefCfg, g_hDefCount,
            g_hDefDenoise, g_hDefSampler;
static HWND g_hSetContent;   /* scrollable inner content window of settings */
static int  g_setScroll = 0, g_setContentH = 0;
static HFONT g_fNorm, g_fBold, g_fSmall;

static void postStatus(const wchar_t *s)
{
    size_t n = (wcslen(s) + 1) * sizeof(wchar_t);
    wchar_t *p = (wchar_t *)GlobalAlloc(GMEM_FIXED, n);
    if (p) { memcpy(p, s, n); PostMessageW(g_hMain, WM_JOB_STATUS, 0, (LPARAM)p); }
}
static void postDone(int ok, const wchar_t *s)
{
    wchar_t *p = NULL;
    if (s) {
        size_t n = (wcslen(s) + 1) * sizeof(wchar_t);
        p = (wchar_t *)GlobalAlloc(GMEM_FIXED, n);
        if (p) memcpy(p, s, n);
    }
    PostMessageW(g_hMain, WM_JOB_DONE, ok, (LPARAM)p);
}

static void joinPath(wchar_t *o, size_t c, const wchar_t *a, const wchar_t *b)
{
    size_t l = wcslen(a);
    if (l && a[l - 1] == L'\\') _snwprintf(o, c, L"%ls%ls", a, b);
    else _snwprintf(o, c, L"%ls\\%ls", a, b);
}
/* data dirs live in the program dir (portable-first); fall back to
 * %LOCALAPPDATA%\LocalDreamET only when the program dir is not writable. */
static void modelsDir(wchar_t *o, size_t c)   { joinPath(o, c, g_baseDir, L"models"); CreateDirectoryW(o, NULL); }
static void outputDir(wchar_t *o, size_t c)   { joinPath(o, c, g_baseDir, L"outputs"); CreateDirectoryW(o, NULL); }
static void tmpDir(wchar_t *o, size_t c)      { joinPath(o, c, g_baseDir, L"tmp"); CreateDirectoryW(o, NULL); }
static void configDir(wchar_t *o, size_t c)   { joinPath(o, c, g_baseDir, L"config"); CreateDirectoryW(o, NULL); }

/* one sub-directory per model: models/<id>/  holds <rel>, <rel>.part, ready.json */
static void modelSubdir(int idx, wchar_t *o, size_t c)
{
    wchar_t md[MAX_PATH]; modelsDir(md, MAX_PATH);
    joinPath(o, c, md, g_models[idx].id);
    CreateDirectoryW(o, NULL);
}
static void modelFile(int idx, int fi, wchar_t *o, size_t c)
{
    wchar_t md[MAX_PATH]; modelSubdir(idx, md, MAX_PATH);
    joinPath(o, c, md, g_models[idx].files[fi].rel);
}
static void modelPart(int idx, int fi, wchar_t *o, size_t c)
{
    wchar_t p[MAX_PATH]; modelFile(idx, fi, p, MAX_PATH);
    _snwprintf(o, c, L"%ls.part", p);
}
static void readyPath(int idx, wchar_t *o, size_t c)
{
    wchar_t md[MAX_PATH]; modelSubdir(idx, md, MAX_PATH);
    joinPath(o, c, md, L"ready.json");
}

/* ---- strict safetensors validation (real, not a magic-byte guess) ----
 * Reads the 8-byte little-endian header length N, parses the JSON header,
 * requires SD1.5 tensor markers, and verifies that 8 + N + max(data_offsets[1])
 * equals the real file size. Rejects HTML/XML error pages and truncation. */
static int validateSafetensors(const wchar_t *path, ULONGLONG *outSize)
{
    HANDLE hf = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL,
                            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hf == INVALID_HANDLE_VALUE) return 0;
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(hf, &sz) || sz.QuadPart < 16) { CloseHandle(hf); return 0; }
    ULONGLONG fsize = (ULONGLONG)sz.QuadPart;

    unsigned char hdr8[8] = {0}; DWORD rd = 0;
    if (!ReadFile(hf, hdr8, 8, &rd, NULL) || rd != 8) { CloseHandle(hf); return 0; }
    if (hdr8[0] == (unsigned char)'<') { CloseHandle(hf); return 0; }  /* HTML error page */
    ULONGLONG n = 0;
    for (int i = 0; i < 8; i++) n |= ((ULONGLONG)hdr8[i]) << (8 * i);
    if (n < 2 || n > 64ULL * 1024 * 1024) { CloseHandle(hf); return 0; }
    if (8 + n + 1024 > fsize) { CloseHandle(hf); return 0; }          /* need tensor bytes */

    char *buf = (char *)malloc((size_t)n + 1);
    if (!buf) { CloseHandle(hf); return 0; }
    if (!ReadFile(hf, buf, (DWORD)n, &rd, NULL) || rd != (DWORD)n) {
        free(buf); CloseHandle(hf); return 0;
    }
    buf[n] = 0;
    CloseHandle(hf);

    if (buf[0] != '{' || !strchr(buf, '}') || !strchr(buf, '"')) { free(buf); return 0; }
    int markers = (strstr(buf, "model.diffusion_model.") != NULL) +
                  (strstr(buf, "cond_stage_model.") != NULL) +
                  (strstr(buf, "first_stage_model.") != NULL);
    if (markers == 0) { free(buf); return 0; }

    ULONGLONG maxend = 0;
    const char *p = buf;
    while ((p = strstr(p, "\"data_offsets\"")) != NULL) {
        p = strchr(p, '[');
        if (!p) break;
        p++;
        unsigned long long a = 0, b = 0;
        if (sscanf(p, " %llu , %llu", &a, &b) >= 1 && b > maxend) maxend = b;
    }
    free(buf);
    if (maxend == 0) return 0;
    if (8 + n + maxend != fsize) return 0;    /* truncated / padded mismatch */
    if (outSize) *outSize = fsize;
    return 1;
}

/* ready.json: {"file":"<rel>","size":<n>,"time":<t>} written via temp+rename */
static void writeReadyJson(int idx, const wchar_t *rel, ULONGLONG sz)
{
    wchar_t dir[MAX_PATH], tmp[MAX_PATH], fin[MAX_PATH];
    modelSubdir(idx, dir, MAX_PATH);
    int nb = WideCharToMultiByte(CP_UTF8, 0, rel, -1, NULL, 0, NULL, NULL);
    char relu8[256] = {0};
    WideCharToMultiByte(CP_UTF8, 0, rel, -1, relu8, nb > 255 ? 255 : nb, NULL, NULL);
    char j[320];
    _snprintf(j, sizeof(j), "{\"file\":\"%s\",\"size\":%llu,\"time\":%ld}\n",
              relu8, sz, (long)time(NULL));
    joinPath(tmp, MAX_PATH, dir, L"ready.json.tmp");
    joinPath(fin, MAX_PATH, dir, L"ready.json");
    HANDLE hf = CreateFileW(tmp, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hf == INVALID_HANDLE_VALUE) return;
    DWORD wr; WriteFile(hf, j, (DWORD)strlen(j), &wr, NULL);
    FlushFileBuffers(hf);
    CloseHandle(hf);
    MoveFileExW(tmp, fin, MOVEFILE_REPLACE_EXISTING);
}
static int readReadyJson(int idx, ULONGLONG *szOut)
{
    wchar_t p[MAX_PATH]; readyPath(idx, p, MAX_PATH);
    HANDLE hf = CreateFileW(p, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (hf == INVALID_HANDLE_VALUE) return 0;
    char buf[400]; DWORD rd = 0;
    ReadFile(hf, buf, sizeof(buf) - 1, &rd, NULL); CloseHandle(hf);
    if (!rd) return 0; buf[rd] = 0;
    char *sp = strstr(buf, "\"size\"");
    if (!sp) return 0; sp = strchr(sp, ':'); if (!sp) return 0;
    *szOut = _strtoui64(sp + 1, NULL, 10);
    return *szOut > 0;
}

/* ready iff: ready.json exists, official file exists with matching size, header still valid */
static int modelReady(int idx)
{
    if (g_models[idx].locked) return 0;
    ULONGLONG recSize = 0;
    if (!readReadyJson(idx, &recSize)) return 0;
    wchar_t p[MAX_PATH]; modelFile(idx, 0, p, MAX_PATH);
    WIN32_FILE_ATTRIBUTE_DATA fa;
    if (!GetFileAttributesExW(p, GetFileExInfoStandard, &fa)) return 0;
    ULONGLONG sz = ((ULONGLONG)fa.nFileSizeHigh << 32) | fa.nFileSizeLow;
    if (sz != recSize) return 0;
    return validateSafetensors(p, NULL) ? 1 : 0;
}

/* startup reconciliation: .part -> resumable; marker w/o file/size mismatch -> reset */
static void reconcileModels(void)
{
    for (int i = 0; i < MODEL_COUNT; i++) {
        if (g_models[i].locked) { g_mstate[i] = 0; g_mpct[i] = -1; continue; }
        if (modelReady(i)) { g_mstate[i] = 1; g_mpct[i] = 100; continue; }
        wchar_t rd[MAX_PATH]; readyPath(i, rd, MAX_PATH);
        DeleteFileW(rd);                                   /* drop stale/bad marker */
        wchar_t pf[MAX_PATH]; modelPart(i, 0, pf, MAX_PATH);
        if (GetFileAttributesW(pf) != INVALID_FILE_ATTRIBUTES) {
            g_mstate[i] = 2; g_mpct[i] = 0;                /* resumable .part */
        } else {
            g_mstate[i] = 0; g_mpct[i] = -1;
        }
    }
}

/* config lives in <basedir>/config/config.ini */
static int g_mirror = 0;     /* 0=auto, 1=hf-mirror, 2=huggingface */
static int g_defSteps = 20, g_defCfg = 7, g_defCount = 1;
static double g_defDenoise = 0.45;
static int g_winMax = 0;
static int g_winX = CW_USEDEFAULT, g_winY = CW_USEDEFAULT, g_winW = 480, g_winH = 900;

static void configPath(wchar_t *o, size_t c)
{
    wchar_t d[MAX_PATH]; configDir(d, MAX_PATH);
    joinPath(o, c, d, L"config.ini");
}
static void loadConfig(void)
{
    wchar_t p[MAX_PATH]; configPath(p, MAX_PATH);
    g_lang = GetPrivateProfileIntW(L"ui", L"lang", 0, p);
    g_dark = GetPrivateProfileIntW(L"ui", L"dark", 1, p);
    GetPrivateProfileStringW(L"ui", L"pinned", L"", g_pinned, 2048, p);
    if (g_lang < 0 || g_lang > 2) g_lang = 0;
    g_mirror = GetPrivateProfileIntW(L"net", L"mirror", 0, p);
    if (g_mirror < 0 || g_mirror > 2) g_mirror = 0;
    g_defSteps = GetPrivateProfileIntW(L"defaults", L"steps", 20, p);
    g_defCfg = GetPrivateProfileIntW(L"defaults", L"cfg", 7, p);
    g_defCount = GetPrivateProfileIntW(L"defaults", L"count", 1, p);
    g_defDenoise = GetPrivateProfileIntW(L"defaults", L"denoise", 45, p) / 100.0;
    g_winX = GetPrivateProfileIntW(L"ui", L"x", CW_USEDEFAULT, p);
    g_winY = GetPrivateProfileIntW(L"ui", L"y", CW_USEDEFAULT, p);
    g_winW = GetPrivateProfileIntW(L"ui", L"w", 480, p);
    g_winH = GetPrivateProfileIntW(L"ui", L"h", 900, p);
    g_winMax = GetPrivateProfileIntW(L"ui", L"max", 0, p);
    if (g_winW < 420) g_winW = 480;
    if (g_winH < 700) g_winH = 900;
}
static void saveConfig(void)
{
    wchar_t p[MAX_PATH]; configPath(p, MAX_PATH);
    wchar_t b[16];
    _snwprintf(b, 16, L"%d", g_lang);      WritePrivateProfileStringW(L"ui", L"lang", b, p);
    _snwprintf(b, 16, L"%d", g_dark);      WritePrivateProfileStringW(L"ui", L"dark", b, p);
    WritePrivateProfileStringW(L"ui", L"pinned", g_pinned, p);
    _snwprintf(b, 16, L"%d", g_mirror);    WritePrivateProfileStringW(L"net", L"mirror", b, p);
    _snwprintf(b, 16, L"%d", g_defSteps);  WritePrivateProfileStringW(L"defaults", L"steps", b, p);
    _snwprintf(b, 16, L"%d", g_defCfg);    WritePrivateProfileStringW(L"defaults", L"cfg", b, p);
    _snwprintf(b, 16, L"%d", g_defCount);  WritePrivateProfileStringW(L"defaults", L"count", b, p);
    _snwprintf(b, 16, L"%d", (int)(g_defDenoise * 100)); WritePrivateProfileStringW(L"defaults", L"denoise", b, p);
    /* window rect (only if not maximized) */
    if (!IsIconic(g_hMain) && !IsZoomed(g_hMain)) {
        WINDOWPLACEMENT wp; wp.length = sizeof(wp);
        if (GetWindowPlacement(g_hMain, &wp)) {
            RECT rc = wp.rcNormalPosition;
            _snwprintf(b, 16, L"%d", rc.left);  WritePrivateProfileStringW(L"ui", L"x", b, p);
            _snwprintf(b, 16, L"%d", rc.top);   WritePrivateProfileStringW(L"ui", L"y", b, p);
            _snwprintf(b, 16, L"%d", rc.right - rc.left); WritePrivateProfileStringW(L"ui", L"w", b, p);
            _snwprintf(b, 16, L"%d", rc.bottom - rc.top); WritePrivateProfileStringW(L"ui", L"h", b, p);
        }
    }
    _snwprintf(b, 16, L"%d", IsZoomed(g_hMain) ? 1 : 0);
    WritePrivateProfileStringW(L"ui", L"max", b, p);
}
static int isPinned(int idx)
{
    wchar_t *ctx = NULL;
    wchar_t *tok = mywcstok(g_pinned, L",", &ctx);
    while (tok) { if (_wcsicmp(tok, g_models[idx].id) == 0) return 1; tok = mywcstok(NULL, L",", &ctx); }
    return 0;
}
static void togglePin(int idx)
{
    wchar_t out[2048] = {0};
    int removing = isPinned(idx);
    wchar_t *ctx = NULL;
    wchar_t *tok = mywcstok(g_pinned, L",", &ctx);
    while (tok) {
        if (!(removing && _wcsicmp(tok, g_models[idx].id) == 0)) {
            if (out[0]) wcscat(out, L",");
            wcscat(out, tok);
        }
        tok = mywcstok(NULL, L",", &ctx);
    }
    if (!removing) { if (out[0]) wcscat(out, L","); wcscat(out, g_models[idx].id); }
    _snwprintf(g_pinned, 2048, L"%ls", out);
    saveConfig();
}

/* ================================ download =============================== */
static BOOL httpGetFile(const char *fullUrl, const wchar_t *dest,
                        void (*prog)(int, void *), void *ctx)
{
    wchar_t wurl[1400];
    MultiByteToWideChar(CP_UTF8, 0, fullUrl, -1, wurl, 1400);
    HINTERNET hN = InternetOpenW(
        L"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        L"(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
    if (!hN) return FALSE;
    InternetSetOptionW(hN, INTERNET_OPTION_CONNECT_TIMEOUT, &(DWORD){20000}, sizeof(DWORD));
    InternetSetOptionW(hN, INTERNET_OPTION_RECEIVE_TIMEOUT, &(DWORD){60000}, sizeof(DWORD));
    InternetSetOptionW(hN, INTERNET_OPTION_SEND_TIMEOUT, &(DWORD){30000}, sizeof(DWORD));

    wchar_t part[MAX_PATH]; _snwprintf(part, MAX_PATH, L"%ls.part", dest);
    WIN32_FILE_ATTRIBUTE_DATA fa;
    ULONGLONG startOff = 0;
    if (GetFileAttributesExW(part, GetFileExInfoStandard, &fa))
        startOff = ((ULONGLONG)fa.nFileSizeHigh << 32) | fa.nFileSizeLow;

    wchar_t hdr[64] = {0};
    if (startOff > 0) _snwprintf(hdr, 64, L"Range: bytes=%llu-\r\n", startOff);

    HINTERNET hU = InternetOpenUrlW(hN, wurl,
        startOff ? hdr : NULL, startOff ? (DWORD)-1L : 0,
        INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_SECURE |
        INTERNET_FLAG_NO_UI | INTERNET_FLAG_PRAGMA_NOCACHE, 0);
    if (!hU) { InternetCloseHandle(hN); return FALSE; }

    wchar_t code[16] = {0}; DWORD csz = sizeof(code);
    HttpQueryInfoW(hU, HTTP_QUERY_STATUS_CODE, code, &csz, NULL);
    int sc = code[0] ? _wtoi(code) : 0;
    if (sc >= 400) { InternetCloseHandle(hU); InternetCloseHandle(hN); return FALSE; }

    if (startOff > 0 && sc != 206) startOff = 0;
    if (sc == 206 && startOff > 0) {
        wchar_t cr[128] = {0}; DWORD crsz = sizeof(cr);
        if (HttpQueryInfoW(hU, HTTP_QUERY_CONTENT_RANGE, cr, &crsz, NULL)) {
            if (wcsstr(cr, L"bytes ") != cr) startOff = 0;
        }
    }

    ULONGLONG total = 0;
    {
        char clen[32] = {0}; DWORD clenSz = sizeof(clen), idxH = 0;
        if (HttpQueryInfoA(hU, HTTP_QUERY_CONTENT_LENGTH, clen, &clenSz, &idxH))
            total = (ULONGLONG)_strtoui64(clen, NULL, 10);
    }
    if (sc == 206 && total) total += startOff;

    HANDLE hf = CreateFileW(part, GENERIC_WRITE, 0, NULL,
                            startOff ? OPEN_EXISTING : CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hf == INVALID_HANDLE_VALUE) { InternetCloseHandle(hU); InternetCloseHandle(hN); return FALSE; }
    if (startOff) SetFilePointer(hf, 0, NULL, FILE_END);

    char buf[128 * 1024]; DWORD got = 0, rd, wr; BOOL ok = TRUE;
    while (InternetReadFile(hU, buf, sizeof(buf), &rd) && rd > 0) {
        if (!WriteFile(hf, buf, rd, &wr, NULL) || wr != rd) { ok = FALSE; break; }
        got += rd;
        if (prog && total) {
            ULONGLONG nowp = startOff + got;
            prog((int)(nowp * 100ULL / total), ctx);
        } else if (prog) {
            prog(-1, ctx);
        }
    }
    CloseHandle(hf); InternetCloseHandle(hU); InternetCloseHandle(hN);

    if (!ok) return FALSE;
    ULONGLONG finalSize = 0;
    if (GetFileAttributesExW(part, GetFileExInfoStandard, &fa))
        finalSize = ((ULONGLONG)fa.nFileSizeHigh << 32) | fa.nFileSizeLow;
    if (total && finalSize < total) return FALSE;
    if (finalSize == 0) return FALSE;
    /* leave <dest>.part on purpose: the caller validates + commits it. */
    return TRUE;
}

static BOOL httpDownload(const char *hfPath, const char *cnUrl, const wchar_t *dest,
                         void (*prog)(int, void *), void *ctx, char *err, size_t errc)
{
    char url[1400];
    #define TRY_SRC(u) do { \
        for (int _att = 0; _att < 2; _att++) { if (httpGetFile((u), dest, prog, ctx)) return TRUE; } \
    } while (0)
    /* mirror selection: 0=auto(hf-mirror->hf), 1=hf-mirror only, 2=huggingface only */
    static const char *auto_hosts[] = { "https://hf-mirror.com", "https://huggingface.co" };
    static const char *mir_hosts[]  = { "https://hf-mirror.com" };
    static const char *hf_hosts[]   = { "https://huggingface.co" };
    const char **hosts = auto_hosts; int nh = 2;
    if (g_mirror == 1) { hosts = mir_hosts; nh = 1; }
    else if (g_mirror == 2) { hosts = hf_hosts; nh = 1; }
    if (cnUrl && cnUrl[0]) TRY_SRC(cnUrl);
    for (int hi = 0; hi < nh; hi++) {
        _snprintf(url, sizeof(url), "%s/%s", hosts[hi], hfPath);
        TRY_SRC(url);
    }
    #undef TRY_SRC
    _snprintf(err, errc, "all mirrors failed (check network/proxy)");
    return FALSE;
}

typedef struct { int model, file, pctOfFile; } DlCtx;
static void dlProgCb(int pct, void *ctx)
{
    DlCtx *c = (DlCtx *)ctx;
    if (pct < 0) return;
    int n = g_models[c->model].nfiles;
    int overall = (c->file * 100 + pct) / n;
    c->pctOfFile = pct; g_mpct[c->model] = overall;
    PostMessageW(g_hMain, WM_JOB_PROGRESS, overall, 0);
    PostMessageW(g_hMain, WM_REFRESH_LIST, 0, 0);
}

static DWORD WINAPI downloadThread(LPVOID arg)
{
    int idx = (int)(INT_PTR)arg;
    DlCtx ctx = { idx, 0, 0 };
    wchar_t st[320];
    _snwprintf(st, 320, T(DOWNLOAD), g_models[idx].name);
    postStatus(st);
    char err[256] = {0}; BOOL ok = TRUE;
    for (int fi = 0; fi < g_models[idx].nfiles; fi++) {
        wchar_t sub[MAX_PATH]; modelSubdir(idx, sub, MAX_PATH);   /* ensure per-model dir */
        wchar_t dest[MAX_PATH]; modelFile(idx, fi, dest, MAX_PATH);
        ctx.file = fi;
        const DlFile *dlf = &g_models[idx].files[fi];
        const char *us = dlf->hf;
        const char *nm = strrchr(us, '/') ? strrchr(us, '/') + 1 : us;
        wchar_t wnm[160]; MultiByteToWideChar(CP_UTF8, 0, nm, -1, wnm, 160);
        _snwprintf(st, 320, L"%ls %ls", T(DOWNLOAD), wnm);
        postStatus(st);
        if (!httpDownload(dlf->hf, dlf->cn, dest, dlProgCb, &ctx, err, sizeof(err))) {
            ok = FALSE; break;
        }
        /* ---- strict validation / finalize phase ---- */
        wchar_t part[MAX_PATH]; modelPart(idx, fi, part, MAX_PATH);
        g_mstate[idx] = 3;                 /* 3 = validating / finishing */
        postStatus(T(VALIDATING));
        PostMessageW(g_hMain, WM_REFRESH_LIST, 0, 0);
        Sleep(60);
        ULONGLONG fsz = 0;
        if (!validateSafetensors(part, &fsz)) {
            DeleteFileW(part);
            _snprintf(err, sizeof(err), "downloaded file is not a valid safetensors model");
            ok = FALSE; break;
        }
        HANDLE hf = CreateFileW(part, GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
        if (hf != INVALID_HANDLE_VALUE) { FlushFileBuffers(hf); CloseHandle(hf); }
        if (!MoveFileExW(part, dest, MOVEFILE_REPLACE_EXISTING)) { ok = FALSE; break; }
        writeReadyJson(idx, g_models[idx].files[fi].rel, fsz);
    }
    if (ok) { g_mstate[idx] = 1; g_mpct[idx] = 100; postDone(1, T(READY)); }
    else {
        g_mstate[idx] = modelReady(idx) ? 1 : 0;
        g_mpct[idx] = (g_mstate[idx] == 1) ? 100 : -1;
        wchar_t werr[320]; MultiByteToWideChar(CP_UTF8, 0, err, -1, werr, 320);
        _snwprintf(st, 320, L"%ls: %ls", T(GEN_FAIL), werr);
        postDone(0, st);
    }
    PostMessageW(g_hMain, WM_REFRESH_LIST, 0, 0);
    return 0;
}

/* ================================ generate =============================== */
static const wchar_t *samplerToCli(const wchar_t *disp)
{
    if (!wcscmp(disp, L"dpm") || !wcscmp(disp, L"dpm_karras")) return L"dpm++2m";
    if (!wcscmp(disp, L"dpm_sde") || !wcscmp(disp, L"dpm_sde_karras")) return L"dpm++2m_sde";
    if (!wcscmp(disp, L"euler") || !wcscmp(disp, L"euler_karras")) return L"euler";
    if (!wcscmp(disp, L"euler_a") || !wcscmp(disp, L"euler_a_karras")) return L"euler_a";
    if (!wcscmp(disp, L"lcm")) return L"lcm";
    return L"dpm++2m";
}

static void sizeFor(int ai, int *W, int *H)
{
    int rw = 1, rh = 1;
    const wchar_t *a = (ai >= 0 && ai < ASPECT_SD15_COUNT) ? g_aspects_sd15[ai] : L"1:1";
    swscanf(a, L"%d:%d", &rw, &rh);
    if (rw <= 0 || rh <= 0) { rw = rh = 1; }
    int edge = 512, align = 64, lo = 128, w, h;
    if (rw >= rh) { w = edge; h = edge * rh / rw; }
    else { h = edge; w = edge * rw / rh; }
    #define ALN(v) do { (v) = (((v) + align/2) / align) * align; if ((v) < lo) (v) = lo; if ((v) > edge) (v) = edge; } while (0)
    ALN(w); ALN(h);
    *W = w; *H = h;
}

static DWORD WINAPI previewWatchThread(LPVOID arg)
{
    (void)arg;
    wchar_t pat[MAX_PATH], tmp[MAX_PATH];
    tmpDir(tmp, MAX_PATH);
    _snwprintf(pat, MAX_PATH, L"%ls\\pv_*.png", tmp);
    while (g_genRunning) {
        WIN32_FIND_DATAW fd;
        HANDLE hf = FindFirstFileW(pat, &fd);
        int maxn = 0; wchar_t newest[MAX_PATH] = {0};
        if (hf != INVALID_HANDLE_VALUE) {
            do {
                int n = 0;
                if (swscanf(fd.cFileName, L"pv_%d.png", &n) == 1 && n >= maxn) {
                    maxn = n; _snwprintf(newest, MAX_PATH, L"%ls\\%ls", tmp, fd.cFileName);
                }
            } while (FindNextFileW(hf, &fd));
            FindClose(hf);
        }
        if (maxn > 0 && newest[0]) {
            int pct = g_genInterval > 0 ? (int)((double)maxn * g_genInterval * 100.0 / g_genSteps) : 0;
            if (pct > 99) pct = 99;
            PostMessageW(g_hMain, WM_JOB_PROGRESS, pct, 0);
            _snwprintf(g_livePath, MAX_PATH, L"%ls", newest);
            PostMessageW(g_hMain, WM_LIVE_PREVIEW, 0, 0);
        }
        Sleep(500);
    }
    return 0;
}

static int runOneGeneration(int idx, const wchar_t *prompt, const wchar_t *neg,
                            int stepsv, double cfgv, const wchar_t *method,
                            int wv, int hv, const wchar_t *seed,
                            double strengthv, int useImg, const wchar_t *out,
                            wchar_t *errOut, size_t errOutC)
{
    wchar_t cli[MAX_PATH]; joinPath(cli, MAX_PATH, g_exeDir, L"sd-cli.exe");
    wchar_t mdl[MAX_PATH]; modelFile(idx, 0, mdl, MAX_PATH);
    wchar_t tmp[MAX_PATH]; tmpDir(tmp, MAX_PATH);
    SYSTEM_INFO si; GetSystemInfo(&si);
    int threads = (int)si.dwNumberOfProcessors; if (threads < 1) threads = 4;
    int interval = stepsv / 15; if (interval < 1) interval = 1; if (interval > stepsv) interval = stepsv;
    g_genSteps = stepsv; g_genInterval = interval;
    wchar_t pvpat[MAX_PATH]; _snwprintf(pvpat, MAX_PATH, L"%ls\\pv_%%03d.png", tmp);

    wchar_t cmd[9000];
    _snwprintf(cmd, 9000,
        L"\"%ls\" -m \"%ls\" -p \"%ls\" -n \"%ls\" --steps %d --cfg-scale %.2f "
        L"--width %d --height %d --sampling-method %ls -t %d --seed %ls "
        L"--preview vae --preview-path \"%ls\" --preview-interval %d -o \"%ls\"",
        cli, mdl, prompt, neg, stepsv, cfgv, wv, hv, method, threads,
        seed, pvpat, interval, out);
    if (useImg) {
        wchar_t extra[700];
        _snwprintf(extra, 700, L" --init-img \"%ls\" --strength %.2f", g_initImg, strengthv);
        wcscat(cmd, extra);
    }

    SECURITY_ATTRIBUTES sa; sa.nLength = sizeof(sa); sa.bInheritHandle = TRUE; sa.lpSecurityDescriptor = NULL;
    HANDLE rd = NULL, wr = NULL;
    if (!CreatePipe(&rd, &wr, &sa, 0)) { _snwprintf(errOut, errOutC, L"pipe"); return -1; }
    SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);
    STARTUPINFOW si2; ZeroMemory(&si2, sizeof(si2)); si2.cb = sizeof(si2);
    si2.dwFlags = STARTF_USESTDHANDLES; si2.hStdOutput = wr; si2.hStdError = wr;
    PROCESS_INFORMATION pi; ZeroMemory(&pi, sizeof(pi));
    if (!CreateProcessW(cli, cmd, NULL, NULL, TRUE,
                        CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS, NULL, g_exeDir, &si2, &pi)) {
        CloseHandle(wr); CloseHandle(rd);
        _snwprintf(errOut, errOutC, L"start"); return -1;
    }
    CloseHandle(wr);
    char obuf[8192]; DWORD nread;
    char tail[800]; tail[0] = 0; size_t tl = 0;
    while (ReadFile(rd, obuf, sizeof(obuf) - 1, &nread, NULL) && nread > 0) {
        obuf[nread] = 0;
        size_t add = nread;
        if (tl + add >= sizeof(tail)) add = sizeof(tail) - 1 - tl;
        memcpy(tail + tl, obuf, add); tl += add; tail[tl] = 0;
        if (tl >= sizeof(tail) - 1) { memmove(tail, tail + 300, tl - 300); tl -= 300; }
    }
    CloseHandle(rd);
    WaitForSingleObject(pi.hProcess, 60 * 60 * 1000);
    DWORD ec = 1; GetExitCodeProcess(pi.hProcess, &ec);
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    MultiByteToWideChar(CP_UTF8, 0, tail, -1, errOut, (int)(errOutC/sizeof(wchar_t)));
    return (int)ec;
}

static DWORD WINAPI generateThread(LPVOID arg)
{
    (void)arg;
    int idx = g_sel;
    if (!modelReady(idx)) { postDone(0, T(NOT_DL)); return 0; }

    wchar_t rawp[4096], rawn[2048], seedtxt[64];
    GetWindowTextW(g_hPrompt, rawp, 4096);
    GetWindowTextW(g_hNeg, rawn, 2048);
    GetWindowTextW(g_hSeed, seedtxt, 64);
    if (wcslen(rawp) == 0) { postDone(0, T(EMPTY_PROMPT)); return 0; }

    wchar_t prompt[6000], neg[6000];
    translatePrompt(rawp, prompt, 6000);
    translatePrompt(rawn, neg, 6000);

    wchar_t wst[16], wcfg[16], wstr[16], wcnt[16];
    GetWindowTextW(g_hSteps, wst, 16); GetWindowTextW(g_hCfg, wcfg, 16);
    GetWindowTextW(g_hDenoise, wstr, 16); GetWindowTextW(g_hCount, wcnt, 16);
    int stepsv = _wtoi(wst); if (stepsv < 1) stepsv = 20; if (stepsv > 50) stepsv = 50;
    double cfgv = _wtof(wcfg); if (cfgv < 1) cfgv = 7; if (cfgv > 30) cfgv = 30;
    int cnt = _wtoi(wcnt); if (cnt < 1) cnt = 1; if (cnt > 4) cnt = 4;
    double strengthv = _wtof(wstr); if (strengthv < 0.1 || strengthv > 1) strengthv = 0.45;
    int useImg = (g_initImg[0] && GetFileAttributesW(g_initImg) != INVALID_FILE_ATTRIBUTES);

    int ai = (int)SendMessageW(g_hAspect, CB_GETCURSEL, 0, 0);
    int wv, hv; sizeFor(ai, &wv, &hv);
    wchar_t sam[32]; GetWindowTextW(g_hSampler, sam, 32);
    const wchar_t *method = samplerToCli(sam);

    wchar_t outdir[MAX_PATH], tmp[MAX_PATH];
    outputDir(outdir, MAX_PATH); tmpDir(tmp, MAX_PATH);
    { wchar_t pat[MAX_PATH]; _snwprintf(pat, MAX_PATH, L"%ls\\pv_*.png", tmp);
      WIN32_FIND_DATAW fd; HANDLE hf = FindFirstFileW(pat, &fd);
      if (hf != INVALID_HANDLE_VALUE) {
          do { wchar_t fp[MAX_PATH]; _snwprintf(fp, MAX_PATH, L"%ls\\%ls", tmp, fd.cFileName);
               DeleteFileW(fp); } while (FindNextFileW(hf, &fd));
          FindClose(hf); } }

    postStatus(T(GENERATING));
    PostMessageW(g_hMain, WM_JOB_PROGRESS, 1, 0);
    InterlockedExchange(&g_genRunning, 1);
    HANDLE hw = CreateThread(NULL, 0, previewWatchThread, NULL, 0, NULL);

    int ok = 0; wchar_t lastErr[1300] = {0};
    for (int b = 0; b < cnt; b++) {
        SYSTEMTIME t; GetLocalTime(&t);
        wchar_t out[MAX_PATH];
        _snwprintf(out, MAX_PATH, L"%ls\\ld_%04d%02d%02d_%02d%02d%02d_%d.png", outdir,
                   t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond, b);
        const wchar_t *seed = (seedtxt[0]) ? seedtxt : L"-1";
        wchar_t err[1000];
        int ec = runOneGeneration(idx, prompt, neg, stepsv, cfgv, method,
                                  wv, hv, seed, strengthv, useImg, out, err, sizeof(err)/sizeof(wchar_t));
        if (ec == 0 && GetFileAttributesW(out) != INVALID_FILE_ATTRIBUTES) {
            wcsncpy(g_lastImage, out, MAX_PATH - 1); ok = 1;
        } else {
            _snwprintf(lastErr, 1300, L"%ls (%lu): %ls", T(GEN_FAIL), (unsigned long)ec, err);
            break;
        }
    }
    InterlockedExchange(&g_genRunning, 0);
    if (hw) { WaitForSingleObject(hw, 1200); CloseHandle(hw); }

    if (ok) {
        wchar_t ps[400];
        _snwprintf(ps, 400, L"%s  %dx%d  steps %d  CFG %.1f", T(DONE), wv, hv, stepsv, cfgv);
        postDone(1, ps);
    } else {
        postDone(0, lastErr);
    }
    return 0;
}

/* =============================== image ctls ============================== */
static void drawImg(HDC hdc, RECT rc, const wchar_t *path, const wchar_t *hint)
{
    HBRUSH bg = CreateSolidBrush(RGB(18, 18, 22));
    FillRect(hdc, &rc, bg); DeleteObject(bg);
    if (!path || !path[0] || GetFileAttributesW(path) == INVALID_FILE_ATTRIBUTES) {
        SetBkMode(hdc, TRANSPARENT); SetTextColor(hdc, RGB(150, 150, 156));
        HFONT f = CreateFontW(15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                              0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
        HFONT old = SelectObject(hdc, f);
        SIZE sz; GetTextExtentPoint32W(hdc, hint, (int)wcslen(hint), &sz);
        TextOutW(hdc, (rc.right - sz.cx) / 2, (rc.bottom - sz.cy) / 2, hint, (int)wcslen(hint));
        SelectObject(hdc, old); DeleteObject(f);
        return;
    }
    HANDLE hf = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                            NULL, OPEN_EXISTING, 0, NULL);
    if (hf == INVALID_HANDLE_VALUE) return;
    DWORD sh = 0, sl = GetFileSize(hf, &sh);
    ULONG fsize = (ULONG)(((ULONGLONG)sh << 32) | sl);
    if (!fsize) { CloseHandle(hf); return; }
    HGLOBAL hg = GlobalAlloc(GMEM_MOVEABLE, fsize);
    void *p = GlobalLock(hg); DWORD rd;
    ReadFile(hf, p, fsize, &rd, NULL); GlobalUnlock(hg); CloseHandle(hf);
    IStream *stream = NULL;
    if (SUCCEEDED(CreateStreamOnHGlobal(hg, FALSE, &stream)) && stream) {
        IPicture *pic = NULL;
        if (SUCCEEDED(OleLoadPicture(stream, fsize, FALSE, &IID_IPicture, (void **)&pic)) && pic) {
            LONG pw = 0, ph = 0;
            pic->lpVtbl->get_Width(pic, &pw); pic->lpVtbl->get_Height(pic, &ph);
            HDC scr = GetDC(NULL);
            int dpx = GetDeviceCaps(scr, LOGPIXELSX), dpy = GetDeviceCaps(scr, LOGPIXELSY);
            ReleaseDC(NULL, scr);
            int iw = MulDiv(pw, dpx, 2540), ih = MulDiv(ph, dpy, 2540);
            if (iw < 1) iw = rc.right; if (ih < 1) ih = rc.bottom;
            double s = (double)(rc.right - 8) / iw;
            if ((double)(rc.bottom - 8) / ih < s) s = (double)(rc.bottom - 8) / ih;
            int dw = (int)(iw * s), dh = (int)(ih * s);
            int x = (rc.right - dw) / 2, y = (rc.bottom - dh) / 2;
            RECT rp; SetRect(&rp, x, y, x + dw, y + dh);
            pic->lpVtbl->Render(pic, hdc, x, y, dw, dh, 0, ph, pw, -ph, &rp);
            pic->lpVtbl->Release(pic);
        }
        stream->lpVtbl->Release(stream);
    }
    GlobalFree(hg);
}

static LRESULT CALLBACK ImgProc(HWND h, UINT m, WPARAM w, LPARAM l)
{
    if (m == WM_PAINT) {
        PAINTSTRUCT ps; HDC hdc = BeginPaint(h, &ps);
        const wchar_t *path = (const wchar_t *)GetWindowLongPtrW(h, GWLP_USERDATA);
        drawImg(hdc, ps.rcPaint, path, T(RESULT_HINT));
        EndPaint(h, &ps); return 0;
    }
    if (m == WM_ERASEBKGND) return 1;
    return DefWindowProcW(h, m, w, l);
}
static void setImgCtl(HWND h, const wchar_t *path)
{
    SetWindowLongPtrW(h, GWLP_USERDATA, (LONG_PTR)path);
    InvalidateRect(h, NULL, TRUE);
}

static LRESULT CALLBACK PanelProc(HWND h, UINT m, WPARAM w, LPARAM l)
{
    if (m == WM_ERASEBKGND) {
        HDC dc = (HDC)w; RECT rc; GetClientRect(h, &rc);
        FillRect(dc, &rc, g_brPanel); return 1;
    }
    if (m == WM_CTLCOLORSTATIC) {
        HDC dc = (HDC)w; SetTextColor(dc, g_cText); SetBkMode(dc, TRANSPARENT);
        return (LRESULT)g_brPanel;
    }
    if (m == WM_CTLCOLOREDIT) {
        HDC dc = (HDC)w; SetTextColor(dc, g_cText); SetBkColor(dc, g_cPanel);
        return (LRESULT)g_brPanel;
    }
    return DefWindowProcW(h, m, w, l);
}

/* ============================ list (model cards) ========================= */
static void buildList(void)
{
    wchar_t filter[128] = {0};
    GetWindowTextW(g_hSearch, filter, 128);
    SendMessageW(g_hList, WM_SETREDRAW, FALSE, 0);
    SendMessageW(g_hList, LB_RESETCONTENT, 0, 0);
    /* collect visible indices, sort pinned first */
    int vis[MODEL_COUNT], nv = 0;
    for (int i = 0; i < MODEL_COUNT; i++) {
        if (g_models[i].tab != g_tab) continue;
        if (filter[0]) {
            wchar_t hay[600];
            _snwprintf(hay, 600, L"%ls %ls %ls %ls %ls", g_models[i].name,
                       g_models[i].descZh, g_models[i].descEn, g_models[i].id, g_models[i].badge);
            _wcslwr(hay);
            wchar_t f2[128]; _snwprintf(f2, 128, L"%ls", filter); _wcslwr(f2);
            if (!StrStrIW(hay, f2)) continue;
        }
        vis[nv++] = i;
    }
    /* bubble: pinned first */
    for (int a = 0; a < nv; a++)
        for (int b = a + 1; b < nv; b++)
            if (!isPinned(vis[a]) && isPinned(vis[b])) { int t = vis[a]; vis[a] = vis[b]; vis[b] = t; }
    int selPos = -1;
    for (int k = 0; k < nv; k++) {
        int pos = (int)SendMessageW(g_hList, LB_ADDSTRING, 0, (LPARAM)L" ");
        SendMessageW(g_hList, LB_SETITEMDATA, pos, (LPARAM)vis[k]);
        if (vis[k] == g_sel) selPos = pos;
    }
    if (selPos >= 0) SendMessageW(g_hList, LB_SETCURSEL, selPos, 0);
    SendMessageW(g_hList, WM_SETREDRAW, TRUE, 0);
    InvalidateRect(g_hList, NULL, TRUE);
}

static void drawCard(LPDRAWITEMSTRUCT d, int ci)
{
    HDC dc = d->hDC; RECT r = d->rcItem;
    int sel = (d->itemState & ODS_SELECTED);
    FillRect(dc, &r, sel ? g_brPanel : g_brCard);
    /* tab color stripe */
    RECT stripe; SetRect(&stripe, r.left, r.top, r.left + 4, r.bottom);
    HBRUSH ab = CreateSolidBrush(g_models[ci].tab == 1 ? RGB(150, 120, 220) : g_cAccent);
    FillRect(dc, &stripe, ab); DeleteObject(ab);

    SetBkMode(dc, TRANSPARENT);
    SelectObject(dc, g_fBold);
    SetTextColor(dc, g_cText);
    RECT rn = r; rn.left += 14; rn.top += 8; rn.right -= 70;
    DrawTextW(dc, g_models[ci].name, -1, &rn, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX);

    SelectObject(dc, g_fSmall); SetTextColor(dc, g_cSub);
    RECT rd2 = r; rd2.left += 14; rd2.top += 30; rd2.right -= 14; rd2.bottom -= 40;
    const wchar_t *desc = g_lang == 0 ? g_models[ci].descZh : g_lang == 1 ? g_models[ci].descEn : g_models[ci].descTw;
    DrawTextW(dc, desc, -1, &rd2, DT_LEFT | DT_END_ELLIPSIS | DT_WORDBREAK);

    RECT rs = r; rs.left += 14; rs.bottom -= 7; rs.top = rs.bottom - 16;
    const wchar_t *sz = g_lang == 1 ? g_models[ci].sizeEn : g_models[ci].sizeZh;
    DrawTextW(dc, sz, -1, &rs, DT_LEFT | DT_SINGLELINE);

    if (isPinned(ci)) {
        SetTextColor(dc, g_cAccent);
        RECT rp = r; rp.left += 14; rp.bottom -= 7; rp.top = rp.bottom - 16;
        /* draw pin marker before size */
        SIZE psz; GetTextExtentPoint32W(dc, sz, (int)wcslen(sz), &psz);
        rp.left += psz.cx + 10;
        DrawTextW(dc, T(PIN), -1, &rp, DT_LEFT | DT_SINGLELINE);
    }

    /* status pill */
    const wchar_t *pill; COLORREF pc;
    static wchar_t buf[64];
    if (g_models[ci].locked) { pill = T(LOCKED); pc = RGB(120, 120, 128); }
    else if (g_mstate[ci] == 3) { pill = T(VALIDATING); pc = RGB(90, 170, 220); }
    else if (g_mstate[ci] == 2) { _snwprintf(buf, 64, T(DOWNLOADING), g_mpct[ci]); pill = buf; pc = RGB(230, 170, 60); }
    else if (modelReady(ci)) { pill = T(READY); pc = RGB(80, 180, 120); }
    else { pill = T(DOWNLOAD); pc = g_cAccent; }
    SIZE sz2; GetTextExtentPoint32W(dc, pill, (int)wcslen(pill), &sz2);
    RECT rp; rp.right = r.right - 10; rp.top = r.top + 8;
    rp.left = rp.right - sz2.cx - 16; rp.bottom = rp.top + sz2.cy + 6;
    HBRUSH pb = CreateSolidBrush(pc); HPEN pn = CreatePen(PS_SOLID, 1, pc);
    SelectObject(dc, pb); SelectObject(dc, pn);
    RoundRect(dc, rp.left, rp.top, rp.right, rp.bottom, 9, 9);
    DeleteObject(pb); DeleteObject(pn);
    SetTextColor(dc, g_dark ? RGB(28, 20, 20) : RGB(255, 255, 255));
    SelectObject(dc, g_fSmall);
    RECT rt = rp; DrawTextW(dc, pill, -1, &rt, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

/* ================================= UI helpers ============================ */
static HWND mk(HWND par, const wchar_t *cls, const wchar_t *txt, DWORD style,
               int x, int y, int w, int h, int id)
{
    return CreateWindowExW(0, cls, txt, WS_CHILD | WS_VISIBLE | style,
                           x, y, w, h, par, (HMENU)(INT_PTR)id, g_hInst, NULL);
}

static void switchTheme(void);
static void applyView(void)
{
    for (int i = 0; i < 3; i++) ShowWindow(g_hPan[i], i == g_view ? SW_SHOW : SW_HIDE);
    ShowWindow(g_hTabCpu, g_view == 0 ? SW_SHOW : SW_HIDE);
    ShowWindow(g_hTabNpu, g_view == 0 ? SW_SHOW : SW_HIDE);
    ShowWindow(g_hSearch, g_view == 0 ? SW_SHOW : SW_HIDE);
    ShowWindow(g_hList, g_view == 0 ? SW_SHOW : SW_HIDE);
    ShowWindow(g_hGear, g_view == 0 ? SW_SHOW : SW_HIDE);
    ShowWindow(g_hLStatus, g_view == 0 ? SW_SHOW : SW_HIDE);
    InvalidateRect(g_hMain, NULL, TRUE);
}

static void refreshTabBtns(void)
{
    SendMessageW(g_hTabCpu, WM_SETFONT, (WPARAM)(g_tab == 0 ? g_fBold : g_fNorm), TRUE);
    SendMessageW(g_hTabNpu, WM_SETFONT, (WPARAM)(g_tab == 1 ? g_fBold : g_fNorm), TRUE);
    InvalidateRect(g_hTabCpu, NULL, TRUE);
    InvalidateRect(g_hTabNpu, NULL, TRUE);
}

static void loadModelIntoRun(int idx)
{
    const CatalogModel *m = &g_models[idx];
    SetWindowTextW(g_hRTitle, m->name);
    SetWindowTextW(g_hPrompt, m->defPrompt);
    {
        wchar_t dn[1000] = {0};
        if (g_hDefNeg) GetWindowTextW(g_hDefNeg, dn, 1000);
        if (m->defNeg[0]) SetWindowTextW(g_hNeg, m->defNeg);
        else if (dn[0]) SetWindowTextW(g_hNeg, dn);
        else SetWindowTextW(g_hNeg,
            L"lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed");
    }
    SendMessageW(g_hAspect, CB_RESETCONTENT, 0, 0);
    for (int i = 0; i < ASPECT_SD15_COUNT; i++)
        SendMessageW(g_hAspect, CB_ADDSTRING, 0, (LPARAM)g_aspects_sd15[i]);
    SendMessageW(g_hAspect, CB_SETCURSEL, 0, 0);
    /* sampler: use persisted default selection */
    SendMessageW(g_hSampler, CB_SETCURSEL, (int)SendMessageW(g_hDefSampler, CB_GETCURSEL, 0, 0), 0);
    wchar_t b[16];
    _snwprintf(b, 16, L"%d", g_defSteps); SetWindowTextW(g_hSteps, b);
    _snwprintf(b, 16, L"%d", g_defCfg);   SetWindowTextW(g_hCfg, b);
    SetWindowTextW(g_hSeed, L"");
    _snwprintf(b, 16, L"%.2f", g_defDenoise); SetWindowTextW(g_hDenoise, b);
    _snwprintf(b, 16, L"%d", g_defCount); SetWindowTextW(g_hCount, b);
    int wv, hv; sizeFor(0, &wv, &hv);
    _snwprintf(b, 16, L"%d", wv); SetWindowTextW(g_hW, b);
    _snwprintf(b, 16, L"%d", hv); SetWindowTextW(g_hH, b);
    g_initImg[0] = 0; setImgCtl(g_hThumb, NULL);
    SetWindowTextW(g_hImgName, T(NO_REF));
    setImgCtl(g_hResult, NULL);
}

static void onAspect(void)
{
    int ai = (int)SendMessageW(g_hAspect, CB_GETCURSEL, 0, 0);
    int wv, hv; sizeFor(ai, &wv, &hv);
    wchar_t b[16]; _snwprintf(b, 16, L"%d", wv); SetWindowTextW(g_hW, b);
    _snwprintf(b, 16, L"%d", hv); SetWindowTextW(g_hH, b);
}

static void enableJobs(BOOL on)
{
    EnableWindow(g_hGen, on);
    EnableWindow(g_hList, on); EnableWindow(g_hSearch, on);
}

static void onCardClicked(void)
{
    int li = (int)SendMessageW(g_hList, LB_GETCURSEL, 0, 0);
    if (li < 0) return;
    g_sel = (int)SendMessageW(g_hList, LB_GETITEMDATA, li, 0);
    const CatalogModel *m = &g_models[g_sel];
    if (m->locked) {
        const wchar_t *why = g_lang == 0 ? m->lockZh : g_lang == 1 ? m->lockEn : m->lockTw;
        wchar_t msg[600]; _snwprintf(msg, 600, L"%ls%ls", T(LOCKED_TAP), why);
        MessageBoxW(g_hMain, msg, m->name, MB_OK | MB_ICONINFORMATION);
        return;
    }
    if (modelReady(g_sel)) {
        loadModelIntoRun(g_sel);
        g_view = 1; applyView();
        return;
    }
    /* confirm download */
    wchar_t ask[300];
    _snwprintf(ask, 300, L"%ls\n%ls  %ls", m->name,
               g_lang == 1 ? m->sizeEn : m->sizeZh, T(DOWNLOAD));
    if (MessageBoxW(g_hMain, ask, T(DOWNLOAD), MB_YESNO | MB_ICONQUESTION) != IDYES) return;
    if (InterlockedExchange(&g_busy, 1) == 1) return;
    g_mstate[g_sel] = 2; g_mpct[g_sel] = 0;
    enableJobs(FALSE); SendMessageW(g_hProgress, PBM_SETPOS, 0, 0);
    HANDLE h = CreateThread(NULL, 0, downloadThread, (LPVOID)(INT_PTR)g_sel, 0, NULL);
    if (h) CloseHandle(h); else { InterlockedExchange(&g_busy, 0); enableJobs(TRUE); }
}

static void onGenerate(void)
{
    if (InterlockedExchange(&g_busy, 1) == 1) return;
    g_view = 1; applyView();
    enableJobs(FALSE); SendMessageW(g_hProgress, PBM_SETPOS, 0, 0);
    HANDLE h = CreateThread(NULL, 0, generateThread, NULL, 0, NULL);
    if (h) CloseHandle(h); else { InterlockedExchange(&g_busy, 0); enableJobs(TRUE); }
}

static void pickImage(void)
{
    wchar_t fn[MAX_PATH] = {0};
    OPENFILENAMEW of; ZeroMemory(&of, sizeof(of));
    of.lStructSize = sizeof(of); of.hwndOwner = g_hMain;
    of.lpstrFilter = L"图片 (*.png;*.jpg;*.jpeg;*.webp)\0*.png;*.jpg;*.jpeg;*.webp\0所有文件\0*.*\0";
    of.lpstrFile = fn; of.nMaxFile = MAX_PATH;
    of.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST;
    if (GetOpenFileNameW(&of)) {
        _snwprintf(g_initImg, MAX_PATH, L"%ls", fn);
        setImgCtl(g_hThumb, g_initImg);
        SetWindowTextW(g_hImgName, PathFindFileNameW(g_initImg));
    }
}

/* ================================ update ================================= */
static BOOL httpGetText(const wchar_t *url, char *out, size_t outc)
{
    HINTERNET hN = InternetOpenW(L"LocalDreamET/3.0", INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
    if (!hN) return FALSE;
    HINTERNET hU = InternetOpenUrlW(hN, url, NULL, 0,
        INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_SECURE | INTERNET_FLAG_NO_UI, 0);
    if (!hU) { InternetCloseHandle(hN); return FALSE; }
    size_t tot = 0; DWORD got; char buf[4096]; out[0] = 0;
    while (InternetReadFile(hU, buf, sizeof(buf) - 1, &got) && got > 0)
        if (tot + got < outc - 1) { memcpy(out + tot, buf, got); tot += got; out[tot] = 0; }
    InternetCloseHandle(hU); InternetCloseHandle(hN);
    return tot > 0;
}
static void jsonStr(const char *j, const char *key, char *out, size_t c)
{
    char pat[64]; _snprintf(pat, sizeof(pat), "\"%s\"", key);
    char *p = strstr(j, pat); out[0] = 0;
    if (!p) return;
    p = strchr(p + strlen(pat), ':'); if (!p) return;
    p++; while (*p == ' ' || *p == '\t') p++;
    if (*p != '"') return;
    p++; size_t i = 0;
    while (*p && *p != '"' && i + 1 < c) out[i++] = *p++;
    out[i] = 0;
}

static DWORD WINAPI updateThread(LPVOID arg)
{
    (void)arg;
    char json[6144] = {0};
    postStatus(T(CHECKING));
    if (!httpGetText(UPDATE_MANIFEST, json, sizeof(json))) {
        postDone(0, T(CHECKING)); return 0;
    }
    int code = 0;
    char *p = strstr(json, "\"versionCode\"");
    if (p) { p = strchr(p, ':'); if (p) code = atoi(p + 1); }
    if (code <= APP_CODE) { postDone(1, T(UP_TO_DATE)); return 0; }

    char url[1024] = {0}, ver[64] = {0}, notes[2048] = {0};
    jsonStr(json, "url", url, sizeof(url));
    jsonStr(json, "version", ver, sizeof(ver));
    jsonStr(json, "notes", notes, sizeof(notes));
    if (!url[0]) { postDone(0, T(UP_TO_DATE)); return 0; }
    wchar_t wver[64], wnotes[2048], ask[2400];
    MultiByteToWideChar(CP_UTF8, 0, ver, -1, wver, 64);
    MultiByteToWideChar(CP_UTF8, 0, notes, -1, wnotes, 2048);
    _snwprintf(ask, 2400, L"%ls\n\n%ls\n\n", wver, wnotes);
    if (MessageBoxW(g_hMain, ask, T(CHECK_UPDATE), MB_YESNO | MB_ICONQUESTION) != IDYES) {
        postDone(1, T(UP_TO_DATE)); return 0;
    }
    wchar_t wurl[1100]; MultiByteToWideChar(CP_UTF8, 0, url, -1, wurl, 1100);
    wchar_t upd[MAX_PATH], dest[MAX_PATH];
    joinPath(upd, MAX_PATH, g_baseDir, L"update"); CreateDirectoryW(upd, NULL);
    _snwprintf(dest, MAX_PATH, L"%ls\\LocalDream-ET-Setup.exe", upd);
    HINTERNET hN = InternetOpenW(L"LocalDreamET/3.0", INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
    HINTERNET hU = InternetOpenUrlW(hN, wurl, NULL, 0,
        INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_SECURE | INTERNET_FLAG_NO_UI, 0);
    BOOL ok = FALSE;
    if (hU) {
        HANDLE hf = CreateFileW(dest, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (hf != INVALID_HANDLE_VALUE) {
            char buf[64 * 1024]; DWORD got, wr, csz = 32;
            char clen[32]; ULONGLONG tot64 = 0, done64 = 0;
            if (HttpQueryInfoA(hU, HTTP_QUERY_CONTENT_LENGTH, clen, &csz, &(DWORD){0}))
                tot64 = _strtoui64(clen, NULL, 10);
            ok = TRUE;
            while (InternetReadFile(hU, buf, sizeof(buf), &got) && got > 0) {
                if (!WriteFile(hf, buf, got, &wr, NULL) || wr != got) { ok = FALSE; break; }
                done64 += got;
                if (tot64) PostMessageW(g_hMain, WM_JOB_PROGRESS, (int)(done64 * 100ULL / tot64), 0);
            }
            CloseHandle(hf);
            if (!ok) DeleteFileW(dest);
        }
        InternetCloseHandle(hU);
    }
    if (hN) InternetCloseHandle(hN);
    if (!ok) { postDone(0, T(CHECKING)); return 0; }
    postDone(1, T(DONE));
    ShellExecuteW(NULL, L"open", dest, NULL, g_baseDir, SW_SHOWNORMAL);
    Sleep(800); PostQuitMessage(0);
    return 0;
}

static void shellOpen(const wchar_t *p)
{
    if (GetFileAttributesW(p) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxW(g_hMain, T(READY_STATUS), T(ABOUT), MB_OK | MB_ICONINFORMATION); return;
    }
    ShellExecuteW(NULL, L"explore", p, NULL, NULL, SW_SHOWNORMAL);
}
static void showAbout(void)
{
    MessageBoxW(g_hMain, T(ABOUT_BODY), T(ABOUT), MB_OK | MB_ICONINFORMATION);
}
static void clearTmp(void)
{
    wchar_t tmp[MAX_PATH], pat[MAX_PATH]; tmpDir(tmp, MAX_PATH);
    _snwprintf(pat, MAX_PATH, L"%ls\\*.*", tmp);
    WIN32_FIND_DATAW fd; HANDLE hf = FindFirstFileW(pat, &fd);
    if (hf != INVALID_HANDLE_VALUE) {
        do { if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
                 wchar_t fp[MAX_PATH]; _snwprintf(fp, MAX_PATH, L"%ls\\%ls", tmp, fd.cFileName);
                 DeleteFileW(fp); } } while (FindNextFileW(hf, &fd));
        FindClose(hf);
    }
    postStatus(T(CLEANED));
}

/* ============================== build UI ================================= */
/* scrollable outer panel for settings: owns a tall inner content window */
static LRESULT CALLBACK ScrollProc(HWND h, UINT m, WPARAM wp, LPARAM lp)
{
    switch (m) {
    case WM_ERASEBKGND: {
        HDC dc = (HDC)wp; RECT rc; GetClientRect(h, &rc);
        FillRect(dc, &rc, g_brPanel); return 1;
    }
    case WM_SIZE: {
        RECT rc; GetClientRect(h, &rc);
        int viewH = rc.bottom - rc.top;
        int maxs = g_setContentH - viewH; if (maxs < 0) maxs = 0;
        if (g_setScroll > maxs) g_setScroll = maxs;
        SCROLLINFO si = { sizeof(si), SIF_RANGE | SIF_PAGE | SIF_POS };
        si.nMin = 0; si.nMax = g_setContentH > 0 ? g_setContentH - 1 : 0;
        si.nPage = viewH; si.nPos = g_setScroll;
        SetScrollInfo(h, SB_VERT, &si, TRUE);
        MoveWindow(g_hSetContent, 0, -g_setScroll, rc.right, g_setContentH, TRUE);
        return 0;
    }
    case WM_VSCROLL: {
        SCROLLINFO si = { sizeof(si), SIF_ALL };
        GetScrollInfo(h, SB_VERT, &si);
        int dy = 0;
        switch (LOWORD(wp)) {
        case SB_LINEUP: dy = -24; break;
        case SB_LINEDOWN: dy = 24; break;
        case SB_PAGEUP: dy = -(int)(si.nPage ? si.nPage : 80); break;
        case SB_PAGEDOWN: dy = (int)(si.nPage ? si.nPage : 80); break;
        case SB_THUMBTRACK: dy = (int)si.nTrackPos - g_setScroll; break;
        }
        g_setScroll += dy;
        RECT rc; GetClientRect(h, &rc);
        int maxs = g_setContentH - (rc.bottom - rc.top); if (maxs < 0) maxs = 0;
        if (g_setScroll < 0) g_setScroll = 0; if (g_setScroll > maxs) g_setScroll = maxs;
        SetScrollPos(h, SB_VERT, g_setScroll, TRUE);
        MoveWindow(g_hSetContent, 0, -g_setScroll, rc.right, g_setContentH, TRUE);
        return 0;
    }
    case WM_MOUSEWHEEL: {
        int d = GET_WHEEL_DELTA_WPARAM(wp) / 24;
        g_setScroll -= d;
        RECT rc; GetClientRect(h, &rc);
        int maxs = g_setContentH - (rc.bottom - rc.top); if (maxs < 0) maxs = 0;
        if (g_setScroll < 0) g_setScroll = 0; if (g_setScroll > maxs) g_setScroll = maxs;
        SetScrollPos(h, SB_VERT, g_setScroll, TRUE);
        MoveWindow(g_hSetContent, 0, -g_setScroll, rc.right, g_setContentH, TRUE);
        return 0;
    }
    }
    return DefWindowProcW(h, m, wp, lp);
}

static void registerClasses(void)
{
    WNDCLASSW wc; ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = ImgProc; wc.hInstance = g_hInst; wc.lpszClassName = L"LDEImg";
    RegisterClassW(&wc);
    ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = PanelProc; wc.hInstance = g_hInst; wc.lpszClassName = L"LDEPanel";
    wc.hbrBackground = g_brPanel;
    RegisterClassW(&wc);
    ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = ScrollProc; wc.hInstance = g_hInst; wc.lpszClassName = L"LDEScroll";
    wc.hbrBackground = g_brPanel;
    RegisterClassW(&wc);
}

static BOOL CALLBACK fontEnum(HWND cw, LPARAM lp)
{
    SendMessageW(cw, WM_SETFONT, (WPARAM)lp, TRUE);
    return TRUE;
}

/* reposition run-page controls on resize (result square follows width) */
static void layoutRun(void)
{
    if (!g_hPan[0]) return;
    RECT rc; GetClientRect(g_hPan[0], &rc);
    int W = rc.right, x = 12, pw = W - 24;
    MoveWindow(g_hRTitle, x + 98, 10, pw - 98, 26, TRUE);
    MoveWindow(g_hPrompt, x, 60, pw, 70, TRUE);
    MoveWindow(g_hNeg, x, 154, pw, 40, TRUE);
    MoveWindow(g_hProgress, x + 148, 368, pw - 148, 20, TRUE);
    MoveWindow(g_hThumb, x + pw - 56, 290, 56, 56, TRUE);
    MoveWindow(g_hImgName, x, 324, pw, 16, TRUE);
    int rs = pw, ry = 406;
    MoveWindow(g_hResult, x, ry, rs, rs, TRUE);
    int by = ry + rs + 8;
    MoveWindow(g_hSave, x, by, 95, 30, TRUE);
    MoveWindow(g_hOpnout, x + 100, by, 100, 30, TRUE);
    MoveWindow(g_hRegen, x + 205, by, 90, 30, TRUE);
    MoveWindow(g_hRStatus, x, by + 36, pw, 30, TRUE);
}

static void relayout(void)
{
    if (!g_hMain) return;
    RECT rc; GetClientRect(g_hMain, &rc);
    int W = rc.right, H = rc.bottom;
    int half = (W - 30) / 2;
    MoveWindow(g_hTabCpu, 12, 10, half, 34, TRUE);
    MoveWindow(g_hTabNpu, 18 + half, 10, half, 34, TRUE);
    MoveWindow(g_hGear, W - 42, 50, 30, 26, TRUE);
    MoveWindow(g_hSearch, 12, 50, W - 84, 26, TRUE);
    MoveWindow(g_hList, 12, 84, W - 24, H - 84 - 32, TRUE);
    MoveWindow(g_hLStatus, 12, H - 28, W - 24, 26, TRUE);
    MoveWindow(g_hPan[0], 0, 0, W, H, TRUE);
    MoveWindow(g_hPan[1], 0, 0, W, H, TRUE);
    MoveWindow(g_hPan[2], 0, 0, W, H, TRUE);
    layoutRun();
}

static void buildUI(HWND h)
{
    g_fNorm = CreateFontW(15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    g_fBold = CreateFontW(15, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    g_fSmall = CreateFontW(12, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");

    int W = 460;
    /* ---- list-view chrome (on main window) ---- */
    g_hTabCpu = mk(h, L"BUTTON", T(TAB_CPU), BS_PUSHBUTTON, 12, 10, (W - 30) / 2, 34, IDC_TABCPU);
    g_hTabNpu = mk(h, L"BUTTON", T(TAB_NPU), BS_PUSHBUTTON, 18 + (W - 30) / 2, 10, (W - 30) / 2, 34, IDC_TABNPU);
    g_hGear = mk(h, L"BUTTON", L"⚙", BS_PUSHBUTTON, W - 42, 50, 30, 26, IDC_GEAR);
    g_hSearch = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_AUTOHSCROLL,
        12, 50, W - 84, 26, h, (HMENU)(INT_PTR)IDC_SEARCH, g_hInst, NULL);
    g_hList = CreateWindowW(L"LISTBOX", NULL,
        WS_CHILD | WS_VISIBLE | WS_VSCROLL | LBS_OWNERDRAWFIXED | LBS_HASSTRINGS | LBS_NOTIFY,
        12, 84, W - 24, 720, h, (HMENU)(INT_PTR)IDC_LIST, g_hInst, NULL);
    SendMessageW(g_hList, LB_SETITEMHEIGHT, 0, MAKELPARAM(78, 0));
    g_hLStatus = mk(h, L"STATIC", T(READY_STATUS), SS_LEFT | SS_ENDELLIPSIS, 12, 872, W - 24, 26, IDC_LSTATUS);

    /* ---- panel 0: run page ---- */
    g_hPan[0] = CreateWindowW(L"LDEPanel", NULL, WS_CHILD, 0, 0, W, 900, h, NULL, g_hInst, NULL);
    HWND pp = g_hPan[0];
    int x = 12, pw = W - 24;
    g_hBack = mk(pp, L"BUTTON", T(BACK), BS_PUSHBUTTON, x, 8, 90, 28, IDC_BACK);
    g_hRTitle = mk(pp, L"STATIC", L"", SS_LEFT, x + 98, 10, pw - 98, 26, IDC_RTITLE);
    SendMessageW(g_hRTitle, WM_SETFONT, (WPARAM)g_fBold, TRUE);
    int py = 42;
    mk(pp, L"STATIC", T(PROMPT_LBL), SS_LEFT, x, py, pw, 16, 0); py += 18;
    g_hPrompt = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_MULTILINE | ES_WANTRETURN | ES_AUTOVSCROLL | WS_VSCROLL,
        x, py, pw, 70, pp, (HMENU)(INT_PTR)IDC_PROMPT, g_hInst, NULL); py += 76;
    mk(pp, L"STATIC", T(NEG_LBL), SS_LEFT, x, py, pw, 16, 0); py += 18;
    g_hNeg = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_MULTILINE | ES_AUTOVSCROLL | WS_VSCROLL,
        x, py, pw, 40, pp, (HMENU)(INT_PTR)IDC_NEG, g_hInst, NULL); py += 48;

    /* row: steps / cfg / count */
    mk(pp, L"STATIC", T(STEPS_LBL), SS_LEFT, x, py + 3, 60, 16, 0);
    g_hSteps = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"20", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 62, py, 44, 24, pp, (HMENU)(INT_PTR)IDC_STEPS, g_hInst, NULL);
    mk(pp, L"STATIC", T(CFG_LBL), SS_LEFT, x + 114, py + 3, 50, 16, 0);
    g_hCfg = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"7", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 166, py, 40, 24, pp, (HMENU)(INT_PTR)IDC_CFG, g_hInst, NULL);
    mk(pp, L"STATIC", T(COUNT_LBL), SS_LEFT, x + 214, py + 3, 50, 16, 0);
    g_hCount = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"1", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 266, py, 36, 24, pp, (HMENU)(INT_PTR)IDC_COUNT, g_hInst, NULL);
    py += 30;

    /* row: aspect / sampler */
    mk(pp, L"STATIC", T(ASPECT_LBL), SS_LEFT, x, py + 3, 60, 16, 0);
    g_hAspect = CreateWindowW(L"COMBOBOX", NULL, WS_CHILD | WS_VISIBLE | CBS_DROPDOWNLIST, x + 62, py - 2, 80, 160, pp, (HMENU)(INT_PTR)IDC_ASPECT, g_hInst, NULL);
    mk(pp, L"STATIC", T(SAMPLER_LBL), SS_LEFT, x + 150, py + 3, 50, 16, 0);
    g_hSampler = CreateWindowW(L"COMBOBOX", NULL, WS_CHILD | WS_VISIBLE | CBS_DROPDOWNLIST, x + 202, py - 2, 150, 180, pp, (HMENU)(INT_PTR)IDC_SAMPLER, g_hInst, NULL);
    for (int i = 0; i < SAMPLER_COUNT; i++) SendMessageW(g_hSampler, CB_ADDSTRING, 0, (LPARAM)g_samplers[i]);
    SendMessageW(g_hSampler, CB_SETCURSEL, 0, 0);
    py += 30;

    /* row: seed / denoise */
    mk(pp, L"STATIC", T(SEED_LBL), SS_LEFT, x, py + 3, 90, 16, 0);
    g_hSeed = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 92, py, 70, 24, pp, (HMENU)(INT_PTR)IDC_SEED, g_hInst, NULL);
    mk(pp, L"STATIC", T(DENOISE_LBL), SS_LEFT, x + 170, py + 3, 70, 16, 0);
    g_hDenoise = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"0.45", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 244, py, 50, 24, pp, (HMENU)(INT_PTR)IDC_DENOISE, g_hInst, NULL);
    py += 30;

    /* row: img2img */
    g_hUpload = mk(pp, L"BUTTON", T(UPLOAD_LBL), BS_PUSHBUTTON, x, py, 150, 28, IDC_UPLOAD);
    g_hClearImg = mk(pp, L"BUTTON", T(REMOVE_IMG), BS_PUSHBUTTON, x + 156, py, 80, 28, IDC_CLEARIMG);
    g_hThumb = CreateWindowW(L"LDEImg", NULL, WS_CHILD | WS_VISIBLE | WS_BORDER, x + pw - 56, py - 2, 56, 56, pp, NULL, g_hInst, NULL);
    g_hImgName = mk(pp, L"STATIC", T(NO_REF), SS_LEFT, x, py + 32, pw, 16, IDC_IMGNAME);
    py += 66;

    /* generate + progress */
    g_hGen = mk(pp, L"BUTTON", T(GENERATE), BS_DEFPUSHBUTTON, x, py, 140, 40, IDC_GEN);
    g_hProgress = CreateWindowExW(0, PROGRESS_CLASSW, NULL, WS_CHILD | WS_VISIBLE | PBS_SMOOTH,
        x + 148, py + 10, pw - 148, 20, pp, NULL, g_hInst, NULL);
    SendMessageW(g_hProgress, PBM_SETRANGE32, 0, 100);
    py += 48;

    /* result image square */
    g_hResult = CreateWindowW(L"LDEImg", NULL, WS_CHILD | WS_VISIBLE | WS_BORDER, x, py, pw, pw, pp, NULL, g_hInst, NULL);
    py += pw + 8;

    /* action buttons row */
    g_hSave = mk(pp, L"BUTTON", T(SAVE_AS), BS_PUSHBUTTON, x, py, 95, 30, IDC_SAVE);
    g_hOpnout = mk(pp, L"BUTTON", T(OPEN_OUT), BS_PUSHBUTTON, x + 100, py, 100, 30, IDC_OPNOUT);
    g_hRegen = mk(pp, L"BUTTON", T(REGENERATE), BS_PUSHBUTTON, x + 205, py, 90, 30, IDC_REGEN);
    py += 36;
    g_hRStatus = mk(pp, L"STATIC", T(READY_STATUS), SS_LEFT | SS_WORDELLIPSIS, x, py, pw, 30, IDC_RSTATUS);

    /* ---- panel 1: settings (scrollable outer + tall inner content) ---- */
    g_hPan[1] = CreateWindowW(L"LDEScroll", NULL, WS_CHILD | WS_VSCROLL, 0, 0, W, 900, h, NULL, g_hInst, NULL);
    g_setContentH = 1180;
    g_hSetContent = CreateWindowW(L"LDEPanel", NULL, WS_CHILD, 0, 0, W, g_setContentH, g_hPan[1], NULL, g_hInst, NULL);
    HWND sp = g_hSetContent;
    int sy = 10;
    g_hSback = mk(sp, L"BUTTON", T(BACK), BS_PUSHBUTTON, x, sy, 90, 28, IDC_SBACK); sy += 44;

    mk(sp, L"STATIC", T(LANG_LBL), SS_LEFT, x, sy, pw, 18, 0); sy += 22;
    g_hLang[0] = mk(sp, L"BUTTON", T(LANG_ZH), BS_AUTORADIOBUTTON, x, sy, 130, 24, IDC_LANG0);
    g_hLang[1] = mk(sp, L"BUTTON", T(LANG_EN), BS_AUTORADIOBUTTON, x + 140, sy, 90, 24, IDC_LANG1);
    g_hLang[2] = mk(sp, L"BUTTON", T(LANG_TW), BS_AUTORADIOBUTTON, x + 240, sy, 110, 24, IDC_LANG2);
    sy += 34;
    mk(sp, L"STATIC", T(THEME_LBL), SS_LEFT, x, sy, pw, 18, 0); sy += 22;
    g_hTheme[0] = mk(sp, L"BUTTON", T(THEME_DARK), BS_AUTORADIOBUTTON, x, sy, 130, 24, IDC_THEME0);
    g_hTheme[1] = mk(sp, L"BUTTON", T(THEME_LIGHT), BS_AUTORADIOBUTTON, x + 140, sy, 130, 24, IDC_THEME1);
    sy += 40;

    mk(sp, L"STATIC", T(MIRROR_LBL), SS_LEFT, x, sy, pw, 18, 0); sy += 22;
    g_hMirror[0] = mk(sp, L"BUTTON", T(MIRROR_AUTO), BS_AUTORADIOBUTTON, x, sy, pw, 22, IDC_MIRROR0); sy += 26;
    g_hMirror[1] = mk(sp, L"BUTTON", T(MIRROR_CN), BS_AUTORADIOBUTTON, x, sy, pw, 22, IDC_MIRROR1); sy += 26;
    g_hMirror[2] = mk(sp, L"BUTTON", T(MIRROR_HF), BS_AUTORADIOBUTTON, x, sy, pw, 22, IDC_MIRROR2); sy += 34;

    mk(sp, L"STATIC", T(DEFAULTS_LBL), SS_LEFT, x, sy, pw, 18, 0); sy += 22;
    mk(sp, L"STATIC", T(STEPS_LBL), SS_LEFT, x, sy + 3, 50, 16, 0);
    g_hDefSteps = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"20", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 52, sy, 44, 24, sp, (HMENU)(INT_PTR)IDC_DEF_STEPS, g_hInst, NULL);
    mk(sp, L"STATIC", T(CFG_LBL), SS_LEFT, x + 104, sy + 3, 40, 16, 0);
    g_hDefCfg = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"7", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 146, sy, 40, 24, sp, (HMENU)(INT_PTR)IDC_DEF_CFG, g_hInst, NULL);
    mk(sp, L"STATIC", T(COUNT_LBL), SS_LEFT, x + 196, sy + 3, 40, 16, 0);
    g_hDefCount = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"1", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 240, sy, 36, 24, sp, (HMENU)(INT_PTR)IDC_DEF_COUNT, g_hInst, NULL);
    mk(sp, L"STATIC", T(DENOISE_LBL), SS_LEFT, x + 286, sy + 3, 60, 16, 0);
    g_hDefDenoise = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"0.45", WS_CHILD | WS_VISIBLE | ES_NUMBER, x + 350, sy, 50, 24, sp, (HMENU)(INT_PTR)IDC_DEF_DENOISE, g_hInst, NULL);
    sy += 32;
    mk(sp, L"STATIC", T(SAMPLER_LBL), SS_LEFT, x, sy + 3, 60, 16, 0);
    g_hDefSampler = CreateWindowW(L"COMBOBOX", NULL, WS_CHILD | CBS_DROPDOWNLIST, x + 62, sy - 2, 180, 200, sp, (HMENU)(INT_PTR)IDC_DEF_SAMPLER, g_hInst, NULL);
    for (int i = 0; i < SAMPLER_COUNT; i++) SendMessageW(g_hDefSampler, CB_ADDSTRING, 0, (LPARAM)g_samplers[i]);
    SendMessageW(g_hDefSampler, CB_SETCURSEL, 0, 0);
    sy += 32;
    mk(sp, L"STATIC", T(NEG_LBL), SS_LEFT, x, sy, pw, 16, 0); sy += 18;
    g_hDefNeg = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | ES_MULTILINE | ES_AUTOVSCROLL | WS_VSCROLL,
        x, sy, pw, 56, sp, (HMENU)(INT_PTR)IDC_DEF_NEG, g_hInst, NULL); sy += 66;

    mk(sp, L"STATIC", T(MODELS_DIR_LBL), SS_LEFT, x, sy, pw, 16, 0); sy += 18;
    g_hOpModels = mk(sp, L"BUTTON", T(OPEN_MODELS), BS_PUSHBUTTON, x, sy, pw, 30, IDC_OPMODELS); sy += 38;
    mk(sp, L"STATIC", T(OUTPUTS_DIR_LBL), SS_LEFT, x, sy, pw, 16, 0); sy += 18;
    mk(sp, L"BUTTON", T(OPEN_OUTPUTS), BS_PUSHBUTTON, x, sy, pw, 30, IDC_OPOUT); sy += 42;

    g_hChkUpd = mk(sp, L"BUTTON", T(CHECK_UPDATE), BS_PUSHBUTTON, x, sy, pw, 32, IDC_CHECKUPD); sy += 38;
    g_hCleanTmp = mk(sp, L"BUTTON", T(CLEAN_TMP), BS_PUSHBUTTON, x, sy, pw, 32, IDC_CLEANTMP); sy += 38;
    g_hAbout = mk(sp, L"BUTTON", T(ABOUT), BS_PUSHBUTTON, x, sy, pw, 32, IDC_ABOUT); sy += 44;
    mk(sp, L"STATIC", T(DL_PATH), SS_LEFT, x, sy, pw, 16, 0); sy += 18;
    mk(sp, L"STATIC", g_baseDir, SS_LEFT | SS_WORDELLIPSIS, x, sy, pw, 40, 0);

    /* panel 2 unused (reserved) */
    g_hPan[2] = CreateWindowW(L"LDEPanel", NULL, WS_CHILD, 0, 0, W, 900, h, NULL, g_hInst, NULL);

    EnumChildWindows(h, fontEnum, (LPARAM)g_fNorm);
    EnumChildWindows(pp, fontEnum, (LPARAM)g_fNorm);
    EnumChildWindows(sp, fontEnum, (LPARAM)g_fNorm);
    SendMessageW(g_hTabCpu, WM_SETFONT, (WPARAM)g_fBold, TRUE);
    SendMessageW(g_hTabNpu, WM_SETFONT, (WPARAM)g_fBold, TRUE);
    refreshTabBtns();
    /* reflect persisted mirror / defaults */
    SendMessageW(g_hMirror[g_mirror], BM_SETCHECK, BST_CHECKED, 0);
    { wchar_t b[16];
      _snwprintf(b,16,L"%d",g_defSteps); SetWindowTextW(g_hDefSteps,b);
      _snwprintf(b,16,L"%d",g_defCfg); SetWindowTextW(g_hDefCfg,b);
      _snwprintf(b,16,L"%d",g_defCount); SetWindowTextW(g_hDefCount,b);
      _snwprintf(b,16,L"%.2f",g_defDenoise); SetWindowTextW(g_hDefDenoise,b); }
    applyView();
}

static void saveAsImage(void)
{
    if (!g_lastImage[0] || GetFileAttributesW(g_lastImage) == INVALID_FILE_ATTRIBUTES) return;
    wchar_t fn[MAX_PATH]; _snwprintf(fn, MAX_PATH, L"LocalDreamET_%ld.png", (long)time(NULL));
    OPENFILENAMEW of; ZeroMemory(&of, sizeof(of));
    of.lStructSize = sizeof(of); of.hwndOwner = g_hMain;
    of.lpstrFilter = L"PNG\0*.png\0"; of.lpstrFile = fn; of.nMaxFile = MAX_PATH;
    of.Flags = OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST;
    if (GetSaveFileNameW(&of)) CopyFileW(g_lastImage, fn, FALSE);
}

static void applyLanguage(void)
{
    wchar_t title[120];
    _snwprintf(title, 120, L"Local Dream ET  v%s", APP_VERSION_STR);
    SetWindowTextW(g_hMain, title);
    SetWindowTextW(g_hTabCpu, T(TAB_CPU));
    SetWindowTextW(g_hTabNpu, T(TAB_NPU));
    SetWindowTextW(g_hSearch, L"");
    SetWindowTextW(g_hLStatus, T(READY_STATUS));
    SetWindowTextW(g_hBack, T(BACK));
    SetWindowTextW(g_hUpload, T(UPLOAD_LBL));
    SetWindowTextW(g_hClearImg, T(REMOVE_IMG));
    SetWindowTextW(g_hGen, T(GENERATE));
    SetWindowTextW(g_hSave, T(SAVE_AS));
    SetWindowTextW(g_hOpnout, T(OPEN_OUT));
    SetWindowTextW(g_hRegen, T(REGENERATE));
    SetWindowTextW(g_hSback, T(BACK));
    SetWindowTextW(g_hLang[0], T(LANG_ZH));
    SetWindowTextW(g_hLang[1], T(LANG_EN));
    SetWindowTextW(g_hLang[2], T(LANG_TW));
    SetWindowTextW(g_hTheme[0], T(THEME_DARK));
    SetWindowTextW(g_hTheme[1], T(THEME_LIGHT));
    SetWindowTextW(g_hChkUpd, T(CHECK_UPDATE));
    SetWindowTextW(g_hCleanTmp, T(CLEAN_TMP));
    SetWindowTextW(g_hOpModels, T(OPEN_MODELS));
    SetWindowTextW(g_hAbout, T(ABOUT));
    SetWindowTextW(g_hMirror[0], T(MIRROR_AUTO));
    SetWindowTextW(g_hMirror[1], T(MIRROR_CN));
    SetWindowTextW(g_hMirror[2], T(MIRROR_HF));
    SendMessageW(g_hLang[g_lang], BM_SETCHECK, BST_CHECKED, 0);
    SendMessageW(g_hTheme[g_dark ? 0 : 1], BM_SETCHECK, BST_CHECKED, 0);
    SendMessageW(g_hMirror[g_mirror], BM_SETCHECK, BST_CHECKED, 0);
    buildList();
}

/* ================================ WndProc ================================ */
static LRESULT CALLBACK MainProc(HWND h, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_CREATE: {
        buildUI(h);
        reconcileModels();
        buildList();
        applyLanguage();
        relayout();
        return 0;
    }

    case WM_SIZE:
        relayout();
        return 0;

    case WM_GETMINMAXINFO: {
        MINMAXINFO *mmi = (MINMAXINFO *)lp;
        mmi->ptMinTrackSize.x = 420;
        mmi->ptMinTrackSize.y = 700;
        return 0;
    }

    case WM_KEYDOWN:
        if (wp == VK_F11) {
            ShowWindow(h, IsZoomed(h) ? SW_RESTORE : SW_MAXIMIZE);
        }
        return 0;

    case WM_ERASEBKGND:
        return 1;

    case WM_DRAWITEM: {
        DRAWITEMSTRUCT *d = (DRAWITEMSTRUCT *)lp;
        if ((UINT)wp == IDC_LIST && d->itemID >= 0)
            drawCard(d, (int)SendMessageW((HWND)d->hwndItem, LB_GETITEMDATA, d->itemID, 0));
        return TRUE;
    }

    case WM_COMMAND: {
        int id = LOWORD(wp), code = HIWORD(wp);
        if (id == IDC_SEARCH && code == EN_CHANGE) buildList();
        else if (id == IDC_LIST && code == LBN_SELCHANGE) {
            /* no-op until double click */
        }
        else if (id == IDC_LIST && code == LBN_DBLCLK) onCardClicked();
        else if (id == IDC_TABCPU) { g_tab = 0; buildList(); refreshTabBtns(); }
        else if (id == IDC_TABNPU) { g_tab = 1; buildList(); refreshTabBtns(); }
        else if (id == IDC_GEAR) { g_view = 2; applyView(); }
        else if (id == IDC_BACK || id == IDC_SBACK) { g_view = 0; applyView(); buildList(); }
        else if (id == IDC_ASPECT && code == CBN_SELCHANGE) onAspect();
        else if (id == IDC_UPLOAD) pickImage();
        else if (id == IDC_CLEARIMG) {
            g_initImg[0] = 0; setImgCtl(g_hThumb, NULL);
            SetWindowTextW(g_hImgName, T(NO_REF));
        }
        else if (id == IDC_GEN || id == IDC_REGEN) onGenerate();
        else if (id == IDC_SAVE) saveAsImage();
        else if (id == IDC_OPNOUT || id == IDC_OPOUT) { wchar_t o[MAX_PATH]; outputDir(o, MAX_PATH); shellOpen(o); }
        else if (id == IDC_OPMODELS) { wchar_t m[MAX_PATH]; modelsDir(m, MAX_PATH); shellOpen(m); }
        else if (id == IDC_LANG0 || id == IDC_LANG1 || id == IDC_LANG2) {
            g_lang = id - IDC_LANG0; saveConfig(); applyLanguage(); switchTheme();
        }
        else if (id == IDC_THEME0 || id == IDC_THEME1) {
            g_dark = (id == IDC_THEME0); saveConfig(); switchTheme();
        }
        else if (id == IDC_MIRROR0 || id == IDC_MIRROR1 || id == IDC_MIRROR2) {
            g_mirror = id - IDC_MIRROR0; saveConfig();
        }
        else if (id == IDC_DEF_STEPS || id == IDC_DEF_CFG || id == IDC_DEF_COUNT || id == IDC_DEF_DENOISE) {
            wchar_t b[16];
            GetWindowTextW(g_hDefSteps, b, 16); g_defSteps = _wtoi(b);
            GetWindowTextW(g_hDefCfg, b, 16); g_defCfg = _wtoi(b);
            GetWindowTextW(g_hDefCount, b, 16); g_defCount = _wtoi(b);
            GetWindowTextW(g_hDefDenoise, b, 16); g_defDenoise = _wtof(b);
            if (g_defSteps < 1) g_defSteps = 20; if (g_defSteps > 50) g_defSteps = 50;
            if (g_defCfg < 1) g_defCfg = 7; if (g_defCfg > 30) g_defCfg = 30;
            if (g_defCount < 1) g_defCount = 1; if (g_defCount > 4) g_defCount = 4;
            if (g_defDenoise < 0.1 || g_defDenoise > 1) g_defDenoise = 0.45;
            saveConfig();
        }
        else if (id == IDC_CHECKUPD) {
            if (InterlockedExchange(&g_busy, 1) == 1) break;
            g_view = 1; applyView(); enableJobs(FALSE);
            HANDLE ht = CreateThread(NULL, 0, updateThread, NULL, 0, NULL);
            if (ht) CloseHandle(ht); else InterlockedExchange(&g_busy, 0);
        }
        else if (id == IDC_CLEANTMP) clearTmp();
        else if (id == IDC_ABOUT) showAbout();
        return 0;
    }

    case WM_RBUTTONUP: {
        /* right-click a card toggles pin */
        POINT pt = { LOWORD(lp), HIWORD(lp) };
        int cnt = (int)SendMessageW(g_hList, LB_GETCOUNT, 0, 0);
        for (int i = 0; i < cnt; i++) {
            RECT ir; SendMessageW(g_hList, LB_GETITEMRECT, i, (LPARAM)&ir);
            if (pt.y >= ir.top && pt.y < ir.bottom) {
                int idx = (int)SendMessageW(g_hList, LB_GETITEMDATA, i, 0);
                if (!g_models[idx].locked) { togglePin(idx); buildList(); }
                break;
            }
        }
        return 0;
    }

    case WM_JOB_PROGRESS:
        if (g_view == 1) SendMessageW(g_hProgress, PBM_SETPOS, (int)wp, 0);
        return 0;

    case WM_LIVE_PREVIEW:
        if (g_livePath[0]) {
            SetWindowLongPtrW(g_hResult, GWLP_USERDATA, (LONG_PTR)g_livePath);
            InvalidateRect(g_hResult, NULL, FALSE);
        }
        return 0;

    case WM_JOB_STATUS:
        if (lp) {
            if (g_view == 1) SetWindowTextW(g_hRStatus, (const wchar_t *)lp);
            else SetWindowTextW(g_hLStatus, (const wchar_t *)lp);
            GlobalFree((HGLOBAL)lp);
        }
        return 0;

    case WM_REFRESH_LIST:
        InvalidateRect(g_hList, NULL, TRUE);
        return 0;

    case WM_JOB_DONE: {
        const wchar_t *s = (const wchar_t *)lp;
        int ok = (int)wp;
        if (s) {
            if (g_view == 1) SetWindowTextW(g_hRStatus, s);
            else SetWindowTextW(g_hLStatus, s);
            GlobalFree((HGLOBAL)lp);
        }
        SendMessageW(g_hProgress, PBM_SETPOS, ok ? 100 : 0, 0);
        enableJobs(TRUE);
        InterlockedExchange(&g_busy, 0);
        if (ok) { setImgCtl(g_hResult, g_lastImage); }
        return 0;
    }

    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLORLISTBOX: {
        HDC dc = (HDC)wp;
        if (g_dark) { SetTextColor(dc, g_cText); SetBkMode(dc, TRANSPARENT); }
        return (LRESULT)(g_dark ? g_brPanel : GetStockObject(WHITE_BRUSH));
    }
    case WM_CTLCOLOREDIT: {
        HDC dc = (HDC)wp;
        if (g_dark) { SetTextColor(dc, g_cText); SetBkColor(dc, g_cPanel); return (LRESULT)g_brPanel; }
        return DefWindowProcW(h, msg, wp, lp);
    }
    case WM_CTLCOLORMSGBOX:
        return 0;

    case WM_EXITSIZEMOVE:
        saveConfig();
        return 0;

    case WM_DESTROY:
        saveConfig();
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
}

static void switchTheme(void)
{
    setupColors();
    SetClassLongPtrW(g_hMain, GCLP_HBRBACKGROUND, (LONG_PTR)g_brBg);
    RECT rc; GetClientRect(g_hMain, &rc);
    InvalidateRect(g_hMain, &rc, TRUE);
    for (int i = 0; i < 3; i++) InvalidateRect(g_hPan[i], NULL, TRUE);
    InvalidateRect(g_hList, NULL, TRUE);
    setImgCtl(g_hResult, g_lastImage); setImgCtl(g_hThumb, g_initImg);
}

/* ================================ entry ================================== */
static int dirWritable(const wchar_t *dir)
{
    wchar_t probe[MAX_PATH];
    _snwprintf(probe, MAX_PATH, L"%ls\\.wtest_%lu", dir, GetTickCount());
    HANDLE hf = CreateFileW(probe, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_HIDDEN, NULL);
    if (hf == INVALID_HANDLE_VALUE) return 0;
    CloseHandle(hf); DeleteFileW(probe);
    return 1;
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR cmd, int show)
{
    (void)hPrev; (void)cmd;
    g_hInst = hInst;
    OleInitialize(NULL);
    INITCOMMONCONTROLSEX icc = { sizeof(icc), ICC_PROGRESS_CLASS | ICC_STANDARD_CLASSES | ICC_LISTVIEW_CLASSES };
    InitCommonControlsEx(&icc);

    GetModuleFileNameW(NULL, g_exeDir, MAX_PATH);
    wchar_t *sl = wcsrchr(g_exeDir, L'\\'); if (sl) sl[1] = 0;

    wchar_t candidate[MAX_PATH];
    _snwprintf(candidate, MAX_PATH, L"%lsmodels", g_exeDir);
    CreateDirectoryW(candidate, NULL);
    if (dirWritable(candidate)) {
        _snwprintf(g_baseDir, MAX_PATH, L"%ls", g_exeDir);
    } else {
        wchar_t local[MAX_PATH] = {0};
        if (SHGetFolderPathW(NULL, CSIDL_LOCAL_APPDATA, NULL, 0, local) == S_OK)
            _snwprintf(g_baseDir, MAX_PATH, L"%ls\\LocalDreamET\\", local);
        else
            _snwprintf(g_baseDir, MAX_PATH, L"%ls", g_exeDir);
    }
    CreateDirectoryW(g_baseDir, NULL);
    { wchar_t d[MAX_PATH];
      modelsDir(d, MAX_PATH); outputDir(d, MAX_PATH); tmpDir(d, MAX_PATH); configDir(d, MAX_PATH);
      joinPath(d, MAX_PATH, g_baseDir, L"update"); CreateDirectoryW(d, NULL); }

    /* one-time migrations from earlier layouts */
    {
        wchar_t oldo[MAX_PATH], newo[MAX_PATH];
        joinPath(oldo, MAX_PATH, g_baseDir, L"output");
        outputDir(newo, MAX_PATH);
        if (GetFileAttributesW(oldo) != INVALID_FILE_ATTRIBUTES &&
            GetFileAttributesW(newo) == INVALID_FILE_ATTRIBUTES)
            MoveFileExW(oldo, newo, MOVEFILE_REPLACE_EXISTING);
        wchar_t oldc[MAX_PATH], newc[MAX_PATH];
        joinPath(oldc, MAX_PATH, g_baseDir, L"config.ini");
        joinPath(newc, MAX_PATH, g_baseDir, L"config\\config.ini");
        if (GetFileAttributesW(oldc) != INVALID_FILE_ATTRIBUTES &&
            GetFileAttributesW(newc) == INVALID_FILE_ATTRIBUTES)
            MoveFileExW(oldc, newc, MOVEFILE_REPLACE_EXISTING);
    }

    loadConfig();
    setupColors();
    registerClasses();

    WNDCLASSW wc; ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = MainProc; wc.hInstance = hInst;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = g_brBg;
    wc.lpszClassName = L"LDEMain3";
    wc.hIcon = LoadIconW(hInst, MAKEINTRESOURCEW(1));
    RegisterClassW(&wc);

    wchar_t title[120];
    _snwprintf(title, 120, L"Local Dream ET  v%s", APP_VERSION_STR);
    g_hMain = CreateWindowW(L"LDEMain3", title,
        WS_OVERLAPPEDWINDOW | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_THICKFRAME,
        g_winX, g_winY, g_winW, g_winH, NULL, NULL, hInst, NULL);
    ShowWindow(g_hMain, g_winMax ? SW_SHOWMAXIMIZED : show);
    UpdateWindow(g_hMain);

    MSG m;
    while (GetMessageW(&m, NULL, 0, 0)) {
        if (m.message == WM_MOUSEWHEEL && g_view == 2 && g_hPan[1])
            SendMessageW(g_hPan[1], WM_MOUSEWHEEL, m.wParam, m.lParam);
        else if (!IsDialogMessageW(g_hMain, &m)) { TranslateMessage(&m); DispatchMessageW(&m); }
    }
    OleUninitialize();
    return 0;
}
