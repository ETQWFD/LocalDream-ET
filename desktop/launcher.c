/*
 * Local Dream ET - Windows desktop launcher  (v2.0)
 * Developer (开发者): ET   Copyright (C) 2026 ET
 *
 * Pure Win32 C front-end around the official stable-diffusion.cpp engine
 * (sd-cli.exe + ggml DLLs, Copyright (c) 2023 leejet, MIT License) shipped in
 * the install folder. Phone-like UI: left searchable model cards with
 * per-model download, right prompt / result / history tabs, image-to-image,
 * dark/light theme, Chinese/English language, live real sampling progress and
 * in-app update. Models live in the program's own "models" folder (portable);
 * when that folder is not writable (Program Files) it falls back to
 * %LOCALAPPDATA%\LocalDreamET.
 *
 * Build:
 *   x86_64-w64-mingw32-gcc -O2 -municode -mwindows launcher.c resource.o \
 *     -o LocalDream-ET.exe -lcomctl32 -lshlwapi -lwininet -lole32 -loleaut32 \
 *     -lgdi32 -luser32 -lshell32 -lcomdlg32 -luuid -static-libgcc
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

#define APP_VERSION L"2.0.0"
#define APP_CODE    2
#define APP_TITLE   L"Local Dream ET  ·  电脑版 v2.0.0  ·  开发者 ET"
#define MAX_CFG_SD  9.0f
#define UPDATE_MANIFEST \
    L"https://etqwfd.github.io/LocalDream-ET/desktop-update.json"

/* =============================== i18n / theme ============================= */

static int g_lang = 0;   /* 0 中文, 1 English */
static int g_dark = 1;

static const wchar_t *S(const wchar_t *zh, const wchar_t *en) { return g_lang ? en : zh; }

static COLORREF g_cBg, g_cPanel, g_cCard, g_cText, g_cSub, g_cAccent;
static HBRUSH g_brBg, g_brPanel, g_brCard;

static void setupColors(void)
{
    if (g_brBg) DeleteObject(g_brBg);
    if (g_brPanel) DeleteObject(g_brPanel);
    if (g_brCard) DeleteObject(g_brCard);
    if (g_dark) {
        g_cBg = RGB(26, 26, 30); g_cPanel = RGB(35, 35, 40); g_cCard = RGB(43, 43, 49);
        g_cText = RGB(236, 232, 230); g_cSub = RGB(160, 156, 158); g_cAccent = RGB(244, 176, 162);
    } else {
        g_cBg = RGB(244, 244, 246); g_cPanel = RGB(255, 255, 255); g_cCard = RGB(255, 255, 255);
        g_cText = RGB(28, 28, 32); g_cSub = RGB(110, 110, 116); g_cAccent = RGB(214, 110, 92);
    }
    g_brBg = CreateSolidBrush(g_cBg);
    g_brPanel = CreateSolidBrush(g_cPanel);
    g_brCard = CreateSolidBrush(g_cCard);
}

/* =============================== model catalog ============================ */

typedef struct { const char *url; const wchar_t *rel; } DlFile;
typedef struct {
    const wchar_t *id, *name, *descZh, *descEn, *sizeZh, *sizeEn, *badge, *defPrompt;
    int kind;       /* 0 SD1.5 single, 1 Qwen2.1 multi-file */
    int nfiles;
    DlFile files[4];
} CatalogModel;

static const CatalogModel g_models[] = {
    { L"absolutereality", L"Absolute Reality 1.8.1",
      L"写实真人，皮肤质感自然，出片稳定", L"Photorealistic people, natural skin, stable",
      L"约 2.1GB", L"~2.1GB", L"SD1.5",
      L"RAW photo, best quality, realistic, photo-realistic, masterpiece, highly detailed skin, 8k uhd, dslr, soft lighting",
      0, 1, { { "digiplay/AbsoluteReality_v1.8.1/resolve/main/absolutereality_v181.safetensors",
              L"AbsoluteReality_v181.safetensors" } } },
    { L"realisticvision", L"Realistic Vision V5.1",
      L"顶级写实人像，电影级光影，少翻车", L"Top photorealistic portraits, cinematic light",
      L"约 2.1GB", L"~2.1GB", L"SD1.5",
      L"RAW photo, best quality, realistic, photo-realistic, masterpiece, detailed skin, 8k uhd, dslr, soft lighting, film grain",
      0, 1, { { "SG161222/Realistic_Vision_V5.1_noVAE/resolve/main/Realistic_Vision_V5.1_fp16-no-ema.safetensors",
              L"Realistic_Vision_V5.1_fp16-no-ema.safetensors" } } },
    { L"majicmix", L"majicMIX Realistic v7",
      L"高质感写实，人像通透高级", L"Premium realistic portraits, high-end look",
      L"约 2.1GB", L"~2.1GB", L"SD1.5",
      L"RAW photo, best quality, masterpiece, photorealistic, 8k uhd, dslr, ultra detailed skin, soft natural lighting, sharp focus, film grain",
      0, 1, { { "digiplay/majicMIX_realistic_v7/resolve/main/majicmixRealistic_v7.safetensors",
              L"majicmixRealistic_v7.safetensors" } } },
    { L"analogmadness", L"Analog Madness v7",
      L"复古胶片写实，胶卷颗粒、自然色彩", L"Vintage analog-film realism, grain, natural color",
      L"约 2.1GB", L"~2.1GB", L"SD1.5",
      L"analog photo, film photography, best quality, masterpiece, realistic, 35mm film, grain, natural color, soft light, dslr",
      0, 1, { { "digiplay/AnalogMadness-realistic-model-v7/resolve/main/analogMadness_v70.safetensors",
              L"analogMadness_v70.safetensors" } } },
    { L"dreamshaper", L"DreamShaper 8",
      L"全能高稳定，写实/插画/动漫皆可、少报错", L"Versatile all-rounder, very stable",
      L"约 2.1GB", L"~2.1GB", L"SD1.5",
      L"masterpiece, best quality, highly detailed, sharp focus, professional, 8k uhd",
      0, 1, { { "digiplay/DreamShaper_8/resolve/main/dreamshaper_8.safetensors",
              L"dreamshaper_8.safetensors" } } },
    { L"counterfeit", L"Counterfeit V2.5",
      L"动漫二次元，高人气画风", L"Anime / 2D style",
      L"约 2.1GB", L"~2.1GB", L"SD1.5",
      L"masterpiece, best quality, 1girl, solo, detailed eyes, anime style",
      0, 1, { { "gsdf/Counterfeit-V2.5/resolve/main/Counterfeit-V2.5_fp16.safetensors",
              L"Counterfeit-V2.5_fp16.safetensors" } } },
    { L"qwen21uc", L"Qwen-Image 2.1 Uncensored (Q4_0)",
      L"新一代大模型，原生懂中文、画质极强。约 10.6GB，建议 16GB 内存/独显，纯 CPU 较慢",
      L"New-gen large model, native Chinese, top quality. ~10.6GB, 16GB/GPU advised; CPU slow",
      L"约 10.6GB（4 个文件）", L"~10.6GB (4 files)", L"QWEN · 大模型",
      L"a lovely cat holding a sign that says 'Local Dream ET', masterpiece, best quality, highly detailed",
      1, 4, {
        { "abenzerps/Qwen-Image-2.1-Uncensored-GGUF/resolve/main/qwen-image-2.1-UC-Q4_0.gguf",
          L"qwenuc\\qwen-image-2.1-UC-Q4_0.gguf" },
        { "Qwen/Qwen3-VL-8B-Instruct-GGUF/resolve/main/Qwen3VL-8B-Instruct-Q4_K_M.gguf",
          L"qwenuc\\Qwen3VL-8B-Instruct-Q4_K_M.gguf" },
        { "Comfy-Org/Qwen-Image-2.1/resolve/main/vae/qwen_image_2.1_vae_bf16.safetensors",
          L"qwenuc\\qwen_image_2.1_vae_bf16.safetensors" },
        { "Qwen/Qwen3-VL-8B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-8B-Instruct-Q8_0.gguf",
          L"qwenuc\\mmproj-Qwen3VL-8B-Instruct-Q8_0.gguf" },
      } },
};
#define MODEL_COUNT (sizeof(g_models) / sizeof(g_models[0]))

static const wchar_t *g_aspects[] = { L"1:1", L"3:4", L"2:3", L"9:16", L"4:3", L"3:2", L"16:9" };
#define ASPECT_COUNT 7

/* ============================ offline ZH -> EN tags ======================= */

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

static void translatePrompt(const wchar_t *in, wchar_t *out, size_t outc)
{
    if (!hasCjk(in)) { _snwprintf(out, outc, L"%ls", in); return; }
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
    wchar_t *tok = wcstok(copy, sep);
    while (tok) {
        if (!hasCjk(tok)) {
            if (res[0] && wcslen(res) + wcslen(tok) + 4 < 5900) { wcscat(res, L", "); wcscat(res, tok); }
            else if (!res[0]) _snwprintf(res, 6000, L"%ls", tok);
        }
        tok = wcstok(NULL, sep);
    }
    if (!res[0]) _snwprintf(out, outc, L"masterpiece, best quality");
    else _snwprintf(out, outc, L"%ls, masterpiece, best quality", res);
}

/* ================================ state / dirs =========================== */

static HINSTANCE g_hInst;
static HWND g_hMain;
static wchar_t g_exeDir[MAX_PATH];
static wchar_t g_baseDir[MAX_PATH];
static wchar_t g_lastImage[MAX_PATH] = {0};
static wchar_t g_initImg[MAX_PATH] = {0};

static volatile LONG g_busy = 0;
static int g_sel = 0;
static int g_mpct[MODEL_COUNT];
static int g_mstate[MODEL_COUNT];
static int g_tab = 0;

static volatile LONG g_genRunning = 0;
static int g_genSteps = 20, g_genInterval = 1;
static wchar_t g_livePath[MAX_PATH] = {0};

#define WM_JOB_STATUS    (WM_APP + 1)
#define WM_JOB_PROGRESS  (WM_APP + 2)
#define WM_JOB_DONE      (WM_APP + 3)
#define WM_REFRESH_LIST  (WM_APP + 4)
#define WM_LIVE_PREVIEW  (WM_APP + 5)

#define IDC_SEARCH 2001
#define IDC_LIST   2002
#define IDC_DL     2003
#define IDC_GEN    2004
#define IDC_TAB0   2005
#define IDC_TAB1   2006
#define IDC_TAB2   2007
#define IDC_PROMPT 2008
#define IDC_NEG    2009
#define IDC_STEPS  2010
#define IDC_CFG    2011
#define IDC_ASPECT 2012
#define IDC_W      2013
#define IDC_H      2014
#define IDC_SAMPLER 2015
#define IDC_SEED   2016
#define IDC_UPLOAD 2017
#define IDC_CLEARIMG 2018
#define IDC_STRENGTH 2019
#define IDC_HIST   2021
#define IDC_SAVE   2022
#define IDC_OPNOUT 2023
#define IDC_REGEN  2024
#define IDC_HISTREF 2025
#define IDC_OPHOUT 2026
#define IDM_LANG_ZH 3001
#define IDM_LANG_EN 3002
#define IDM_THEME_D 3003
#define IDM_THEME_L 3004
#define IDM_UPDATE  3005
#define IDM_OPMDIR  3006
#define IDM_OPODIR  3007
#define IDM_ABOUT   3008

static HWND g_hSearch, g_hList, g_hBtnDl, g_hBtnGen;
static HWND g_hPan[3], g_hTab[3];
static HWND g_hTitle, g_hSub, g_hPrompt, g_hNeg, g_hSteps, g_hCfg, g_hAspect,
            g_hW, g_hH, g_hSampler, g_hSeed, g_hUpload, g_hClearImg, g_hStrength,
            g_hProgress, g_hStatus, g_hResult, g_hThumb, g_hHist, g_hHistThumb,
            g_hParams, g_hImgName;
static HFONT g_fNorm, g_fBold, g_fSmall, g_fTitle;

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
static void modelsDir(wchar_t *o, size_t c)   { joinPath(o, c, g_baseDir, L"models"); CreateDirectoryW(o, NULL); }
static void outputDir(wchar_t *o, size_t c)   { joinPath(o, c, g_baseDir, L"output"); CreateDirectoryW(o, NULL); }
static void tmpDir(wchar_t *o, size_t c)      { joinPath(o, c, g_baseDir, L"tmp"); CreateDirectoryW(o, NULL); }
static void modelFile(int idx, int fi, wchar_t *o, size_t c)
{
    wchar_t md[MAX_PATH]; modelsDir(md, MAX_PATH);
    joinPath(o, c, md, g_models[idx].files[fi].rel);
}
static int modelReady(int idx)
{
    for (int i = 0; i < g_models[idx].nfiles; i++) {
        wchar_t p[MAX_PATH]; modelFile(idx, i, p, MAX_PATH);
        if (GetFileAttributesW(p) == INVALID_FILE_ATTRIBUTES) return 0;
    }
    return 1;
}

/* config */
static void configPath(wchar_t *o, size_t c) { joinPath(o, c, g_baseDir, L"config.ini"); }
static void loadConfig(void)
{
    wchar_t p[MAX_PATH]; configPath(p, MAX_PATH);
    g_lang = GetPrivateProfileIntW(L"ui", L"lang", 0, p);
    g_dark = GetPrivateProfileIntW(L"ui", L"dark", 1, p);
    if (g_lang < 0 || g_lang > 1) g_lang = 0;
}
static void saveConfig(void)
{
    wchar_t p[MAX_PATH]; configPath(p, MAX_PATH);
    WritePrivateProfileStringW(L"ui", L"lang", g_lang ? L"1" : L"0", p);
    WritePrivateProfileStringW(L"ui", L"dark", g_dark ? L"1" : L"0", p);
}

/* ================================ download =============================== */

static BOOL httpDownload(const char *path, const wchar_t *dest,
                         void (*prog)(int, void *), void *ctx, char *err, size_t errc)
{
    static const char *hosts[] = { "https://hf-mirror.com", "https://huggingface.co" };
    for (int hi = 0; hi < 2; hi++) {
        char url[1200]; _snprintf(url, sizeof(url), "%s/%s", hosts[hi], path);
        wchar_t wurl[1300]; MultiByteToWideChar(CP_UTF8, 0, url, -1, wurl, 1300);
        HINTERNET hN = InternetOpenW(L"LocalDreamET/2.0", INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
        if (!hN) continue;
        HINTERNET hU = InternetOpenUrlW(hN, wurl, NULL, 0,
            INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_SECURE | INTERNET_FLAG_NO_UI, 0);
        if (!hU) { InternetCloseHandle(hN); continue; }
        wchar_t code[16] = {0}; DWORD csz = sizeof(code);
        HttpQueryInfoW(hU, HTTP_QUERY_STATUS_CODE, code, &csz, NULL);
        if (code[0] && _wtoi(code) >= 400) { InternetCloseHandle(hU); InternetCloseHandle(hN); continue; }
        DWORD total = 0, idxH = 0; char clen[32]; DWORD clenSz = sizeof(clen);
        if (HttpQueryInfoA(hU, HTTP_QUERY_CONTENT_LENGTH, clen, &clenSz, &idxH))
            total = (DWORD)_strtoui64(clen, NULL, 10);
        wchar_t part[MAX_PATH]; _snwprintf(part, MAX_PATH, L"%ls.part", dest);
        DWORD startOff = 0;
        WIN32_FILE_ATTRIBUTE_DATA fa;
        if (GetFileAttributesExW(part, GetFileExInfoStandard, &fa))
            startOff = (DWORD)(((ULONGLONG)fa.nFileSizeHigh << 32) | fa.nFileSizeLow);
        HANDLE hf = CreateFileW(part, GENERIC_WRITE, 0, NULL,
                                startOff ? OPEN_EXISTING : CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (hf == INVALID_HANDLE_VALUE) { InternetCloseHandle(hU); InternetCloseHandle(hN); continue; }
        if (startOff) SetFilePointer(hf, 0, NULL, FILE_END);
        char buf[64 * 1024]; DWORD got = 0, readn; BOOL ok = TRUE;
        while (InternetReadFile(hU, buf, sizeof(buf), &readn) && readn > 0) {
            DWORD wr;
            if (!WriteFile(hf, buf, readn, &wr, NULL) || wr != readn) { ok = FALSE; break; }
            got += readn;
            if (prog) prog(total > 0 ? (int)((double)(startOff + got) * 100.0 / total) : -1, ctx);
        }
        CloseHandle(hf); InternetCloseHandle(hU); InternetCloseHandle(hN);
        if (ok && got > 0) { MoveFileExW(part, dest, MOVEFILE_REPLACE_EXISTING); return TRUE; }
    }
    _snprintf(err, errc, "all hosts failed");
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
    _snwprintf(st, 320, S(L"正在下载 %ls …", L"Downloading %ls …"), g_models[idx].name);
    postStatus(st);
    char err[256] = {0}; BOOL ok = TRUE;
    for (int fi = 0; fi < g_models[idx].nfiles; fi++) {
        wchar_t dest[MAX_PATH]; modelFile(idx, fi, dest, MAX_PATH);
        if (GetFileAttributesW(dest) != INVALID_FILE_ATTRIBUTES) continue;
        wchar_t sub[MAX_PATH]; _snwprintf(sub, MAX_PATH, L"%ls", dest);
        wchar_t *sl = wcsrchr(sub, L'\\'); if (sl) { sl[0] = 0; CreateDirectoryW(sub, NULL); }
        ctx.file = fi;
        const char *us = g_models[idx].files[fi].url;
        const char *nm = strrchr(us, '/') ? strrchr(us, '/') + 1 : us;
        wchar_t wnm[160]; MultiByteToWideChar(CP_UTF8, 0, nm, -1, wnm, 160);
        _snwprintf(st, 320, S(L"下载 %ls（%d/%d）", L"Downloading %ls (%d/%d)"),
                   wnm, fi + 1, g_models[idx].nfiles);
        postStatus(st);
        if (!httpDownload(us, dest, dlProgCb, &ctx, err, sizeof(err))) { ok = FALSE; break; }
    }
    if (ok) { g_mstate[idx] = 1; g_mpct[idx] = 100; postDone(1, S(L"模型下载完成", L"Model downloaded")); }
    else {
        g_mstate[idx] = modelReady(idx) ? 1 : 0;
        wchar_t werr[320]; MultiByteToWideChar(CP_UTF8, 0, err, -1, werr, 320);
        _snwprintf(st, 320, S(L"下载失败（可重试，支持断点续传）：%ls", L"Download failed (resumable): %ls"), werr);
        postDone(0, st);
    }
    PostMessageW(g_hMain, WM_REFRESH_LIST, 0, 0);
    return 0;
}

/* ================================ generate =============================== */

static void sizeFor(int kind, int ai, int *W, int *H)
{
    int rw = 1, rh = 1;
    if (ai >= 0 && ai < ASPECT_COUNT) swscanf(g_aspects[ai], L"%d:%d", &rw, &rh);
    if (rw <= 0 || rh <= 0) { rw = rh = 1; }
    int edge = kind == 1 ? 1024 : 512;
    int align = kind == 1 ? 32 : 64;
    int lo = kind == 1 ? 512 : 128, w, h;
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

static DWORD WINAPI generateThread(LPVOID arg)
{
    (void)arg;
    int idx = g_sel;
    const CatalogModel *m = &g_models[idx];
    if (!modelReady(idx)) { postDone(0, S(L"模型未下载，请先在左侧下载", L"Model not downloaded yet")); return 0; }

    wchar_t rawp[4096], rawn[2048], seedtxt[64];
    GetWindowTextW(g_hPrompt, rawp, 4096);
    GetWindowTextW(g_hNeg, rawn, 2048);
    GetWindowTextW(g_hSeed, seedtxt, 64);
    if (wcslen(rawp) == 0) { postDone(0, S(L"提示词不能为空", L"Prompt empty")); return 0; }

    wchar_t prompt[6000], neg[6000];
    if (m->kind == 0) { translatePrompt(rawp, prompt, 6000); translatePrompt(rawn, neg, 6000); }
    else { _snwprintf(prompt, 6000, L"%ls", rawp); _snwprintf(neg, 6000, L"%ls", rawn); }

    wchar_t wst[16], wcfg[16], wstr[16];
    GetWindowTextW(g_hSteps, wst, 16); GetWindowTextW(g_hCfg, wcfg, 16);
    int stepsv = _wtoi(wst); if (stepsv < 1) stepsv = 22;
    if (m->kind == 0) { if (stepsv > 60) stepsv = 60; } else if (stepsv > 40) stepsv = 40;
    double cfgv = _wtof(wcfg);
    if (cfgv <= 0) cfgv = m->kind == 1 ? 6.0 : 7.0;
    if (m->kind == 0 && cfgv > MAX_CFG_SD) cfgv = MAX_CFG_SD;

    int ai = (int)SendMessageW(g_hAspect, CB_GETCURSEL, 0, 0);
    int wv, hv; sizeFor(m->kind, ai, &wv, &hv);

    wchar_t sam[32]; GetWindowTextW(g_hSampler, sam, 32);
    const wchar_t *method = (m->kind == 1) ? L"euler"
        : (wcscmp(sam, L"euler") == 0 ? L"euler" : wcscmp(sam, L"dpm++2m") == 0 ? L"dpm++2m" : L"euler_a");

    GetWindowTextW(g_hStrength, wstr, 16);
    double strengthv = _wtof(wstr); if (strengthv <= 0.05 || strengthv > 1) strengthv = 0.6;
    int useImg = (g_initImg[0] && GetFileAttributesW(g_initImg) != INVALID_FILE_ATTRIBUTES);

    SYSTEM_INFO si; GetSystemInfo(&si);
    int threads = (int)si.dwNumberOfProcessors; if (threads < 1) threads = 4;

    wchar_t outdir[MAX_PATH], tmp[MAX_PATH]; outputDir(outdir, MAX_PATH); tmpDir(tmp, MAX_PATH);
    SYSTEMTIME t; GetLocalTime(&t);
    wchar_t out[MAX_PATH];
    _snwprintf(out, MAX_PATH, L"%ls\\ld_%04d%02d%02d_%02d%02d%02d.png", outdir,
               t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond);

    wchar_t cli[MAX_PATH]; joinPath(cli, MAX_PATH, g_exeDir, L"sd-cli.exe");
    if (GetFileAttributesW(cli) == INVALID_FILE_ATTRIBUTES) {
        postDone(0, S(L"未找到引擎 sd-cli.exe，请重新安装", L"sd-cli.exe missing, reinstall")); return 0;
    }
    {
        wchar_t pat[MAX_PATH]; _snwprintf(pat, MAX_PATH, L"%ls\\pv_*.png", tmp);
        WIN32_FIND_DATAW fd; HANDLE hf = FindFirstFileW(pat, &fd);
        if (hf != INVALID_HANDLE_VALUE) {
            do { wchar_t fp[MAX_PATH]; _snwprintf(fp, MAX_PATH, L"%ls\\%ls", tmp, fd.cFileName);
                 DeleteFileW(fp); } while (FindNextFileW(hf, &fd));
            FindClose(hf);
        }
    }
    int interval = stepsv / 15; if (interval < 1) interval = 1; if (interval > stepsv) interval = stepsv;
    g_genSteps = stepsv; g_genInterval = interval;
    wchar_t pvpat[MAX_PATH]; _snwprintf(pvpat, MAX_PATH, L"%ls\\pv_%%03d.png", tmp);
    const wchar_t *seed = (seedtxt[0] && _wtol(seedtxt) >= 0) ? seedtxt : L"-1";

    wchar_t cmd[9000];
    if (m->kind == 1) {
        wchar_t dit[MAX_PATH], vae[MAX_PATH], llm[MAX_PATH], mmproj[MAX_PATH];
        modelFile(idx, 0, dit, MAX_PATH); modelFile(idx, 1, llm, MAX_PATH);
        modelFile(idx, 2, vae, MAX_PATH); modelFile(idx, 3, mmproj, MAX_PATH);
        int hasVision = (GetFileAttributesW(mmproj) != INVALID_FILE_ATTRIBUTES);
        _snwprintf(cmd, 9000,
            L"\"%ls\" --diffusion-model \"%ls\" --vae \"%ls\" --llm \"%ls\" "
            L"-p \"%ls\" --cfg-scale %.1f --sampling-method %ls --width %d --height %d "
            L"-t %d --seed %ls --offload-to-cpu --fa "
            L"--preview vae --preview-path \"%ls\" --preview-interval %d -o \"%ls\"",
            cli, dit, vae, llm, prompt, cfgv, method, wv, hv, threads,
            seed, pvpat, interval, out);
        if (useImg && hasVision) {
            wchar_t extra[700];
            _snwprintf(extra, 700, L" -r \"%ls\" --llm_vision \"%ls\"", g_initImg, mmproj);
            wcscat(cmd, extra);
        }
    } else {
        wchar_t mdl[MAX_PATH]; modelFile(idx, 0, mdl, MAX_PATH);
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
    }

    postStatus(m->kind == 1
        ? S(L"Qwen 大模型生成中（纯 CPU 较慢，请看实时预览）…", L"Qwen generating (slow on CPU, live preview)…")
        : S(L"正在本地生成，请稍候（实时预览）…", L"Generating locally (live preview)…"));
    PostMessageW(g_hMain, WM_JOB_PROGRESS, 1, 0);

    SECURITY_ATTRIBUTES sa; sa.nLength = sizeof(sa); sa.bInheritHandle = TRUE; sa.lpSecurityDescriptor = NULL;
    HANDLE rd = NULL, wr = NULL;
    if (!CreatePipe(&rd, &wr, &sa, 0)) { postDone(0, L"pipe"); return 0; }
    SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);
    STARTUPINFOW si2; ZeroMemory(&si2, sizeof(si2)); si2.cb = sizeof(si2);
    si2.dwFlags = STARTF_USESTDHANDLES; si2.hStdOutput = wr; si2.hStdError = wr;
    PROCESS_INFORMATION pi; ZeroMemory(&pi, sizeof(pi));
    if (!CreateProcessW(cli, cmd, NULL, NULL, TRUE,
                        CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS, NULL, g_exeDir, &si2, &pi)) {
        CloseHandle(wr); CloseHandle(rd);
        postDone(0, S(L"启动引擎失败，缺少运行库，请重新安装", L"Engine start failed, reinstall")); return 0;
    }
    CloseHandle(wr);
    InterlockedExchange(&g_genRunning, 1);
    HANDLE hw = CreateThread(NULL, 0, previewWatchThread, NULL, 0, NULL);
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
    InterlockedExchange(&g_genRunning, 0);
    if (hw) { WaitForSingleObject(hw, 1200); CloseHandle(hw); }

    if (ec == 0 && GetFileAttributesW(out) != INVALID_FILE_ATTRIBUTES) {
        wcsncpy(g_lastImage, out, MAX_PATH - 1);
        wchar_t ps[400];
        _snwprintf(ps, 400, S(L"完成  %d×%d · 步数 %d · CFG %.1f · %ls%ls",
                              L"Done  %dx%d · steps %d · CFG %.1f · %ls%ls"),
                   wv, hv, stepsv, cfgv, method, useImg ? S(L" · 图生图", L" · img2img") : L"");
        postDone(1, ps);
    } else {
        wchar_t wtail[820]; MultiByteToWideChar(CP_UTF8, 0, tail, -1, wtail, 820);
        wchar_t msg[1300];
        _snwprintf(msg, 1300, S(L"生成失败（退出码 %lu）：%ls", L"Generate failed (code %lu): %ls"), ec, wtail);
        postDone(0, msg);
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
        HFONT f = CreateFontW(16, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
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
        drawImg(hdc, ps.rcPaint, path, S(L"生成结果显示在这里", L"Result appears here"));
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
    for (size_t i = 0; i < MODEL_COUNT; i++) {
        int show = 1;
        if (filter[0]) {
            wchar_t hay[500];
            _snwprintf(hay, 500, L"%ls %ls %ls %ls", g_models[i].name,
                       g_lang ? g_models[i].descEn : g_models[i].descZh,
                       g_models[i].id, g_models[i].badge);
            show = (StrStrIW(hay, filter) != NULL);
        }
        if (show) {
            int pos = (int)SendMessageW(g_hList, LB_ADDSTRING, 0, (LPARAM)L" ");
            SendMessageW(g_hList, LB_SETITEMDATA, pos, (LPARAM)i);
        }
    }
    int n = (int)SendMessageW(g_hList, LB_GETCOUNT, 0, 0), found = -1;
    for (int i = 0; i < n; i++)
        if ((int)SendMessageW(g_hList, LB_GETITEMDATA, i, 0) == g_sel) { found = i; break; }
    if (found >= 0) SendMessageW(g_hList, LB_SETCURSEL, found, 0);
    SendMessageW(g_hList, WM_SETREDRAW, TRUE, 0);
    InvalidateRect(g_hList, NULL, TRUE);
}

static void drawCard(LPDRAWITEMSTRUCT d, int ci)
{
    HDC dc = d->hDC; RECT r = d->rcItem;
    int sel = (d->itemState & ODS_SELECTED);
    FillRect(dc, &r, sel ? g_brPanel : g_brCard);
    RECT stripe; SetRect(&stripe, r.left, r.top, r.left + 5, r.bottom);
    HBRUSH ab = CreateSolidBrush(g_models[ci].kind == 1 ? RGB(150, 120, 220) : g_cAccent);
    FillRect(dc, &stripe, ab); DeleteObject(ab);

    SetBkMode(dc, TRANSPARENT);
    HFONT old = SelectObject(dc, g_fBold);
    SetTextColor(dc, g_cText);
    RECT rn = r; rn.left += 14; rn.top += 8; rn.right -= 96;
    DrawTextW(dc, g_models[ci].name, -1, &rn, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX);

    SelectObject(dc, g_fSmall); SetTextColor(dc, g_cSub);
    RECT rd2 = r; rd2.left += 14; rd2.top += 32; rd2.right -= 14; rd2.bottom -= 24;
    DrawTextW(dc, g_lang ? g_models[ci].descEn : g_models[ci].descZh, -1, &rd2,
              DT_LEFT | DT_END_ELLIPSIS | DT_WORDBREAK);

    RECT rs = r; rs.left += 14; rs.bottom -= 6; rs.top = rs.bottom - 18;
    DrawTextW(dc, g_lang ? g_models[ci].sizeEn : g_models[ci].sizeZh, -1, &rs, DT_LEFT | DT_SINGLELINE);

    const wchar_t *pill; COLORREF pc;
    static wchar_t buf[40];
    if (g_mstate[ci] == 2) { _snwprintf(buf, 40, S(L"下载中 %d%%", L"%d%%"), g_mpct[ci]); pill = buf; pc = RGB(230, 170, 60); }
    else if (modelReady(ci)) { pill = S(L"✓ 已下载", L"✓ Ready"); pc = RGB(80, 180, 120); }
    else { pill = S(L"下载", L"Download"); pc = g_cAccent; }
    SIZE sz; GetTextExtentPoint32W(dc, pill, (int)wcslen(pill), &sz);
    RECT rp; rp.right = r.right - 10; rp.top = r.top + 9;
    rp.left = rp.right - sz.cx - 18; rp.bottom = rp.top + sz.cy + 8;
    HBRUSH pb = CreateSolidBrush(pc); HPEN pn = CreatePen(PS_SOLID, 1, pc);
    SelectObject(dc, pb); SelectObject(dc, pn);
    RoundRect(dc, rp.left, rp.top, rp.right, rp.bottom, 10, 10);
    DeleteObject(pb); DeleteObject(pn);
    SetTextColor(dc, g_dark ? RGB(28, 20, 20) : RGB(255, 255, 255));
    SelectObject(dc, g_fSmall);
    RECT rt = rp; DrawTextW(dc, pill, -1, &rt, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(dc, old);
}

/* ================================= UI helpers ============================ */

static HWND mk(HWND par, const wchar_t *cls, const wchar_t *txt, DWORD style,
               int x, int y, int w, int h, int id)
{
    return CreateWindowExW(0, cls, txt, WS_CHILD | WS_VISIBLE | style,
                           x, y, w, h, par, (HMENU)(INT_PTR)id, g_hInst, NULL);
}

static void applyTab(void)
{
    for (int i = 0; i < 3; i++) {
        ShowWindow(g_hPan[i], i == g_tab ? SW_SHOW : SW_HIDE);
        SendMessageW(g_hTab[i], WM_SETFONT, (WPARAM)(i == g_tab ? g_fBold : g_fNorm), TRUE);
    }
}

static void refreshSelectionUI(void)
{
    const CatalogModel *m = &g_models[g_sel];
    SetWindowTextW(g_hTitle, m->name);
    SetWindowTextW(g_hSub, g_lang ? m->descEn : m->descZh);
    SetWindowTextW(g_hPrompt, m->defPrompt);
    int wv, hv; sizeFor(m->kind, (int)SendMessageW(g_hAspect, CB_GETCURSEL, 0, 0), &wv, &hv);
    wchar_t b[16];
    _snwprintf(b, 16, L"%d", wv); SetWindowTextW(g_hW, b);
    _snwprintf(b, 16, L"%d", hv); SetWindowTextW(g_hH, b);
    int ready = modelReady(g_sel);
    SetWindowTextW(g_hBtnDl, ready ? S(L"已下载，可直接生成", L"Ready to generate")
                                   : S(L"⬇ 下载此模型", L"⬇ Download this model"));
    EnableWindow(g_hBtnDl, !ready);
    SetWindowTextW(g_hCfg, m->kind == 1 ? L"6" : L"7");
    SendMessageW(g_hSampler, CB_SETCURSEL, m->kind == 1 ? 1 : 0, TRUE);
    InvalidateRect(g_hList, NULL, TRUE);
}

static void applyLanguage(void)
{
    SetWindowTextW(g_hMain, APP_TITLE);
    SetWindowTextW(g_hTab[0], S(L"提示词", L"Prompt"));
    SetWindowTextW(g_hTab[1], S(L"生成结果", L"Result"));
    SetWindowTextW(g_hTab[2], S(L"历史", L"History"));
    SetWindowTextW(g_hUpload, S(L"📷 上传图片做图生图（整张使用，不裁剪）", L"📷 Upload image (img2img, whole image)"));
    SetWindowTextW(g_hClearImg, S(L"移除图片", L"Remove image"));
    SetWindowTextW(g_hBtnGen, S(L"✨ 生成图像", L"✨ Generate"));
    refreshSelectionUI();
}

static void onAspect(void)
{
    int ai = (int)SendMessageW(g_hAspect, CB_GETCURSEL, 0, 0);
    int wv, hv; sizeFor(g_models[g_sel].kind, ai, &wv, &hv);
    wchar_t b[16];
    _snwprintf(b, 16, L"%d", wv); SetWindowTextW(g_hW, b);
    _snwprintf(b, 16, L"%d", hv); SetWindowTextW(g_hH, b);
}

static void enableJobs(BOOL on)
{
    EnableWindow(g_hBtnGen, on);
    EnableWindow(g_hBtnDl, on && !modelReady(g_sel));
    EnableWindow(g_hList, on); EnableWindow(g_hSearch, on);
}

static void onDownload(void)
{
    if (InterlockedExchange(&g_busy, 1) == 1) return;
    int li = (int)SendMessageW(g_hList, LB_GETCURSEL, 0, 0);
    if (li >= 0) g_sel = (int)SendMessageW(g_hList, LB_GETITEMDATA, li, 0);
    if (modelReady(g_sel)) { InterlockedExchange(&g_busy, 0); return; }
    g_mstate[g_sel] = 2; g_mpct[g_sel] = 0;
    enableJobs(FALSE); SendMessageW(g_hProgress, PBM_SETPOS, 0, 0);
    HANDLE h = CreateThread(NULL, 0, downloadThread, (LPVOID)(INT_PTR)g_sel, 0, NULL);
    if (h) CloseHandle(h); else { InterlockedExchange(&g_busy, 0); enableJobs(TRUE); }
}

static void onGenerate(void)
{
    if (InterlockedExchange(&g_busy, 1) == 1) return;
    int li = (int)SendMessageW(g_hList, LB_GETCURSEL, 0, 0);
    if (li >= 0) g_sel = (int)SendMessageW(g_hList, LB_GETITEMDATA, li, 0);
    g_tab = 1; applyTab();
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
        SetWindowTextW(g_hStatus, S(L"已选择参考图（图生图，整张使用，不分割）", L"Reference selected (whole image)"));
    }
}

static void fillHistory(void)
{
    SendMessageW(g_hHist, LB_RESETCONTENT, 0, 0);
    wchar_t od[MAX_PATH], pat[MAX_PATH], names[400][MAX_PATH]; int n = 0;
    outputDir(od, MAX_PATH);
    _snwprintf(pat, MAX_PATH, L"%ls\\*.png", od);
    WIN32_FIND_DATAW fd; HANDLE hf = FindFirstFileW(pat, &fd);
    if (hf != INVALID_HANDLE_VALUE) {
        do { if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && n < 400)
                  _snwprintf(names[n++], MAX_PATH, L"%ls", fd.cFileName);
        } while (FindNextFileW(hf, &fd));
        FindClose(hf);
    }
    for (int i = n - 1; i >= 0; i--) {
        int pos = (int)SendMessageW(g_hHist, LB_ADDSTRING, 0, (LPARAM)names[i]);
        wchar_t full[MAX_PATH]; _snwprintf(full, MAX_PATH, L"%ls\\%ls", od, names[i]);
        wchar_t *dup = _wcsdup(full);
        SendMessageW(g_hHist, LB_SETITEMDATA, pos, (LPARAM)dup);
    }
}

/* ================================ update ================================= */

static BOOL httpGetText(const wchar_t *url, char *out, size_t outc)
{
    HINTERNET hN = InternetOpenW(L"LocalDreamET/2.0", INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
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
    postStatus(S(L"正在检查更新…", L"Checking for updates…"));
    if (!httpGetText(UPDATE_MANIFEST, json, sizeof(json))) {
        postDone(0, S(L"无法连接更新服务器", L"Cannot reach update server")); return 0;
    }
    int code = 0;
    char *p = strstr(json, "\"versionCode\"");
    if (p) { p = strchr(p, ':'); if (p) code = atoi(p + 1); }
    if (code <= APP_CODE) { postDone(1, S(L"已是最新版本（v2.0.0）", L"Already up to date (v2.0.0)")); return 0; }

    char url[1024] = {0}, ver[64] = {0}, notes[2048] = {0};
    jsonStr(json, "url", url, sizeof(url));
    jsonStr(json, "version", ver, sizeof(ver));
    jsonStr(json, "notes", notes, sizeof(notes));
    if (!url[0]) { postDone(0, S(L"更新清单缺少下载地址", L"Manifest missing url")); return 0; }
    wchar_t wver[64], wnotes[2048], ask[2400];
    MultiByteToWideChar(CP_UTF8, 0, ver, -1, wver, 64);
    MultiByteToWideChar(CP_UTF8, 0, notes, -1, wnotes, 2048);
    _snwprintf(ask, 2400, S(L"发现新版本 %ls\n\n%ls\n\n是否立即下载并安装？",
                            L"New version %ls\n\n%ls\n\nDownload and install now?"), wver, wnotes);
    if (MessageBoxW(g_hMain, ask, S(L"软件更新", L"Update"), MB_YESNO | MB_ICONQUESTION) != IDYES) {
        postDone(1, S(L"已取消更新", L"Update cancelled")); return 0;
    }
    wchar_t wurl[1100]; MultiByteToWideChar(CP_UTF8, 0, url, -1, wurl, 1100);
    wchar_t upd[MAX_PATH], dest[MAX_PATH];
    joinPath(upd, MAX_PATH, g_baseDir, L"update"); CreateDirectoryW(upd, NULL);
    _snwprintf(dest, MAX_PATH, L"%ls\\LocalDream-ET-Setup.exe", upd);
    postStatus(S(L"正在下载更新安装包…", L"Downloading installer…"));

    HINTERNET hN = InternetOpenW(L"LocalDreamET/2.0", INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
    HINTERNET hU = InternetOpenUrlW(hN, wurl, NULL, 0,
        INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_SECURE | INTERNET_FLAG_NO_UI, 0);
    BOOL ok = FALSE;
    if (hU) {
        HANDLE hf = CreateFileW(dest, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (hf != INVALID_HANDLE_VALUE) {
            char buf[64 * 1024]; DWORD got, wr, idx = 0, csz = 32;
            char clen[32]; ULONGLONG tot64 = 0, done64 = 0;
            if (HttpQueryInfoA(hU, HTTP_QUERY_CONTENT_LENGTH, clen, &csz, &idx))
                tot64 = _strtoui64(clen, NULL, 10);
            ok = TRUE;
            while (InternetReadFile(hU, buf, sizeof(buf), &got) && got > 0) {
                if (!WriteFile(hf, buf, got, &wr, NULL) || wr != got) { ok = FALSE; break; }
                done64 += got;
                if (tot64) PostMessageW(g_hMain, WM_JOB_PROGRESS,
                                        (int)(done64 * 100ULL / tot64), 0);
            }
            CloseHandle(hf);
            if (!ok) DeleteFileW(dest);
        }
        InternetCloseHandle(hU);
    }
    if (hN) InternetCloseHandle(hN);
    if (!ok) { postDone(0, S(L"更新下载失败", L"Update download failed")); return 0; }
    postDone(1, S(L"下载完成，即将启动安装程序（软件会关闭）", L"Downloaded; launching installer (app closes)"));
    ShellExecuteW(NULL, L"open", dest, NULL, g_baseDir, SW_SHOWNORMAL);
    Sleep(800); PostQuitMessage(0);
    return 0;
}

/* ================================= menus ================================= */

static HMENU buildMenu(void)
{
    HMENU bar = CreateMenu(), set = CreatePopupMenu();
    AppendMenuW(set, MF_STRING, IDM_LANG_ZH, S(L"语言：中文", L"Language: Chinese"));
    AppendMenuW(set, MF_STRING, IDM_LANG_EN, S(L"语言：English", L"Language: English"));
    AppendMenuW(set, MF_SEPARATOR, 0, NULL);
    AppendMenuW(set, MF_STRING, IDM_THEME_D, S(L"主题：深色", L"Theme: Dark"));
    AppendMenuW(set, MF_STRING, IDM_THEME_L, S(L"主题：浅色", L"Theme: Light"));
    AppendMenuW(set, MF_SEPARATOR, 0, NULL);
    AppendMenuW(set, MF_STRING, IDM_UPDATE, S(L"检测更新", L"Check for updates"));
    AppendMenuW(set, MF_STRING, IDM_OPMDIR, S(L"打开模型文件夹", L"Open models folder"));
    AppendMenuW(set, MF_STRING, IDM_OPODIR, S(L"打开图片输出文件夹", L"Open output folder"));
    AppendMenuW(bar, MF_POPUP, (UINT_PTR)set, S(L"设置", L"Settings"));
    HMENU help = CreatePopupMenu();
    AppendMenuW(help, MF_STRING, IDM_ABOUT, S(L"关于 Local Dream ET", L"About Local Dream ET"));
    AppendMenuW(bar, MF_POPUP, (UINT_PTR)help, S(L"帮助", L"Help"));
    CheckMenuItem(set, IDM_LANG_ZH, MF_BYCOMMAND | (g_lang ? MF_UNCHECKED : MF_CHECKED));
    CheckMenuItem(set, IDM_LANG_EN, MF_BYCOMMAND | (g_lang ? MF_CHECKED : MF_UNCHECKED));
    CheckMenuItem(set, IDM_THEME_D, MF_BYCOMMAND | (g_dark ? MF_CHECKED : MF_UNCHECKED));
    CheckMenuItem(set, IDM_THEME_L, MF_BYCOMMAND | (g_dark ? MF_UNCHECKED : MF_CHECKED));
    return bar;
}
static void refreshMenu(void)
{
    HMENU old = GetMenu(g_hMain);
    SetMenu(g_hMain, buildMenu());
    if (old) DestroyMenu(old);
    DrawMenuBar(g_hMain);
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
    refreshMenu();
}
static void shellOpen(const wchar_t *p)
{
    if (GetFileAttributesW(p) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxW(g_hMain, S(L"该文件夹将在首次下载/生成后创建。", L"Folder appears after first use."),
                    APP_TITLE, MB_OK | MB_ICONINFORMATION); return;
    }
    ShellExecuteW(NULL, L"explore", p, NULL, NULL, SW_SHOWNORMAL);
}
static void showAbout(void)
{
    MessageBoxW(g_hMain,
        L"Local Dream ET  电脑版 v2.0.0\n\n"
        L"开发者 / Developer：ET\nCopyright (C) 2026 ET\n\n"
        L"本地离线 Stable Diffusion 出图，免费、不上传图片。\n"
        L"引擎 stable-diffusion.cpp（sd-cli / ggml）\n"
        L"Copyright (c) 2023 leejet · MIT License（保留原作者署名）\n\n"
        L"模型位于程序目录 models（不可写时用 %LOCALAPPDATA%\\LocalDreamET）。",
        S(L"关于", L"About"), MB_OK | MB_ICONINFORMATION);
}

/* ============================== build UI ================================= */

static void registerClasses(void)
{
    WNDCLASSW wc; ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = ImgProc; wc.hInstance = g_hInst; wc.lpszClassName = L"LDEImg";
    RegisterClassW(&wc);
    ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = PanelProc; wc.hInstance = g_hInst; wc.lpszClassName = L"LDEPanel";
    wc.hbrBackground = g_brPanel;
    RegisterClassW(&wc);
}

static BOOL CALLBACK fontEnum(HWND cw, LPARAM lp)
{
    SendMessageW(cw, WM_SETFONT, (WPARAM)lp, TRUE);
    return TRUE;
}

static void buildUI(HWND h)
{
    g_fNorm = CreateFontW(16, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    g_fBold = CreateFontW(16, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    g_fSmall = CreateFontW(13, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    g_fTitle = CreateFontW(23, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");

    mk(h, L"STATIC", S(L"搜索模型（中文/英文/型号）…", L"Search models…"), SS_LEFT, 16, 10, 300, 18, 0);
    g_hSearch = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_AUTOHSCROLL,
        16, 30, 300, 28, h, (HMENU)(INT_PTR)IDC_SEARCH, g_hInst, NULL);
    g_hList = CreateWindowW(L"LISTBOX", NULL,
        WS_CHILD | WS_VISIBLE | WS_VSCROLL | WS_BORDER | LBS_OWNERDRAWFIXED | LBS_HASSTRINGS | LBS_NOTIFY,
        16, 66, 300, 600, h, (HMENU)(INT_PTR)IDC_LIST, g_hInst, NULL);
    SendMessageW(g_hList, LB_SETITEMHEIGHT, 0, MAKELPARAM(80, 0));
    g_hBtnDl = mk(h, L"BUTTON", S(L"⬇ 下载此模型", L"⬇ Download this model"), BS_PUSHBUTTON, 16, 674, 300, 36, IDC_DL);

    int rx = 348, rw = 824;
    g_hTitle = mk(h, L"STATIC", L"", SS_LEFT, rx, 12, rw - 20, 32, 0);
    g_hSub  = mk(h, L"STATIC", L"", SS_LEFT, rx, 46, rw - 20, 20, 0);
    g_hTab[0] = mk(h, L"BUTTON", S(L"提示词", L"Prompt"), BS_PUSHBUTTON, rx, 76, 120, 32, IDC_TAB0);
    g_hTab[1] = mk(h, L"BUTTON", S(L"生成结果", L"Result"), BS_PUSHBUTTON, rx + 126, 76, 120, 32, IDC_TAB1);
    g_hTab[2] = mk(h, L"BUTTON", S(L"历史", L"History"), BS_PUSHBUTTON, rx + 252, 76, 120, 32, IDC_TAB2);

    for (int i = 0; i < 3; i++)
        g_hPan[i] = CreateWindowW(L"LDEPanel", NULL, WS_CHILD, rx, 116, rw, 600, h, NULL, g_hInst, NULL);
    HWND pp = g_hPan[0];
    int py = 10;
    mk(pp, L"STATIC", S(L"图像生成提示（可直接输中文，自动转英文标签）", L"Prompt (Chinese auto-translated to tags)"), SS_LEFT, 12, py, rw - 24, 20, 0); py += 22;
    g_hPrompt = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_MULTILINE | ES_WANTRETURN | ES_AUTOVSCROLL | WS_VSCROLL,
        12, py, rw - 24, 92, pp, (HMENU)(INT_PTR)IDC_PROMPT, g_hInst, NULL);
    py += 100;
    mk(pp, L"STATIC", S(L"负面提示", L"Negative prompt"), SS_LEFT, 12, py, 200, 18, 0); py += 20;
    g_hNeg = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT",
        L"lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_MULTILINE | ES_AUTOVSCROLL | WS_VSCROLL,
        12, py, rw - 24, 60, pp, (HMENU)(INT_PTR)IDC_NEG, g_hInst, NULL);
    py += 68;

    g_hUpload = mk(pp, L"BUTTON", S(L"📷 上传图片做图生图（整张使用，不分割）", L"📷 Upload image (img2img, whole image)"), BS_PUSHBUTTON, 12, py, 400, 32, IDC_UPLOAD);
    g_hClearImg = mk(pp, L"BUTTON", S(L"移除图片", L"Remove"), BS_PUSHBUTTON, 420, py, 100, 32, IDC_CLEARIMG);
    g_hThumb = CreateWindowW(L"LDEImg", NULL, WS_CHILD | WS_VISIBLE | WS_BORDER, 530, py - 52, 84, 84, pp, NULL, g_hInst, NULL);
    g_hImgName = mk(pp, L"STATIC", S(L"未选择参考图（纯文生图）", L"No reference (text-to-image)"), SS_LEFT, 12, py + 38, 500, 18, 0);
    mk(pp, L"STATIC", S(L"重绘强度", L"Denoise"), SS_LEFT, 12, py + 62, 70, 18, 0);
    g_hStrength = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"0.6",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_NUMBER, 84, py + 59, 54, 24,
        pp, (HMENU)(INT_PTR)IDC_STRENGTH, g_hInst, NULL);
    py += 96;

    mk(pp, L"STATIC", S(L"步数", L"Steps"), SS_LEFT, 12, py + 4, 40, 18, 0);
    g_hSteps = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"22",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_NUMBER, 52, py, 52, 26, pp, (HMENU)(INT_PTR)IDC_STEPS, g_hInst, NULL);
    mk(pp, L"STATIC", S(L"CFG", L"CFG"), SS_LEFT, 114, py + 4, 40, 18, 0);
    g_hCfg = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"7",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_NUMBER, 150, py, 50, 26, pp, (HMENU)(INT_PTR)IDC_CFG, g_hInst, NULL);
    mk(pp, L"STATIC", S(L"比例", L"Aspect"), SS_LEFT, 210, py + 4, 50, 18, 0);
    g_hAspect = CreateWindowW(L"COMBOBOX", NULL,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | CBS_DROPDOWNLIST | WS_VSCROLL,
        258, py - 2, 92, 220, pp, (HMENU)(INT_PTR)IDC_ASPECT, g_hInst, NULL);
    for (int i = 0; i < ASPECT_COUNT; i++) SendMessageW(g_hAspect, CB_ADDSTRING, 0, (LPARAM)g_aspects[i]);
    SendMessageW(g_hAspect, CB_SETCURSEL, 0, 0);
    g_hW = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"512",
        WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL | ES_READONLY,
        360, py, 56, 26, pp, (HMENU)(INT_PTR)IDC_W, g_hInst, NULL);
    mk(pp, L"STATIC", L"×", SS_CENTER, 418, py + 4, 14, 18, 0);
    g_hH = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"512",
        WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL | ES_READONLY,
        432, py, 56, 26, pp, (HMENU)(INT_PTR)IDC_H, g_hInst, NULL);
    mk(pp, L"STATIC", S(L"SD1.5按64对齐 / Qwen按32", L"SD mult of 64 / Qwen 32"), SS_LEFT, 498, py + 4, 240, 18, 0);
    py += 34;

    mk(pp, L"STATIC", S(L"采样器", L"Sampler"), SS_LEFT, 12, py + 4, 50, 18, 0);
    g_hSampler = CreateWindowW(L"COMBOBOX", NULL,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | CBS_DROPDOWNLIST,
        62, py - 2, 120, 120, pp, (HMENU)(INT_PTR)IDC_SAMPLER, g_hInst, NULL);
    SendMessageW(g_hSampler, CB_ADDSTRING, 0, (LPARAM)L"euler_a");
    SendMessageW(g_hSampler, CB_ADDSTRING, 0, (LPARAM)L"euler");
    SendMessageW(g_hSampler, CB_ADDSTRING, 0, (LPARAM)L"dpm++2m");
    SendMessageW(g_hSampler, CB_SETCURSEL, 0, 0);
    mk(pp, L"STATIC", S(L"种子(-1随机)", L"Seed(-1 random)"), SS_LEFT, 196, py + 4, 130, 18, 0);
    g_hSeed = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"-1",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_AUTOHSCROLL,
        326, py - 2, 120, 26, pp, (HMENU)(INT_PTR)IDC_SEED, g_hInst, NULL);
    py += 38;

    g_hBtnGen = mk(pp, L"BUTTON", S(L"✨ 生成图像", L"✨ Generate"), BS_DEFPUSHBUTTON, 12, py, 220, 46, IDC_GEN);
    g_hProgress = CreateWindowExW(0, PROGRESS_CLASSW, NULL, WS_CHILD | WS_VISIBLE | PBS_SMOOTH,
        246, py + 12, rw - 270, 22, pp, NULL, g_hInst, NULL);
    SendMessageW(g_hProgress, PBM_SETRANGE32, 0, 100);
    py += 56;
    g_hStatus = mk(pp, L"STATIC",
        S(L"就绪：左侧选择模型并下载，然后输入提示词生成。", L"Ready: pick + download a model, then generate."),
        SS_LEFT | SS_WORDELLIPSIS, 12, py, rw - 24, 44, 0);

    HWND prr = g_hPan[1];
    g_hResult = CreateWindowW(L"LDEImg", NULL, WS_CHILD | WS_VISIBLE | WS_BORDER, 12, 12, 460, 540, prr, NULL, g_hInst, NULL);
    mk(prr, L"BUTTON", S(L"💾 另存为…", L"💾 Save as…"), BS_PUSHBUTTON, 492, 14, 160, 36, IDC_SAVE);
    mk(prr, L"BUTTON", S(L"📂 打开输出文件夹", L"📂 Open output"), BS_PUSHBUTTON, 492, 58, 190, 36, IDC_OPNOUT);
    mk(prr, L"BUTTON", S(L"🔁 相同参数重新生成", L"🔁 Regenerate"), BS_PUSHBUTTON, 492, 102, 190, 36, IDC_REGEN);
    g_hParams = mk(prr, L"STATIC", S(L"生成参数会显示在这里", L"Generation parameters appear here"), SS_LEFT, 492, 156, 320, 220, 0);

    HWND ph = g_hPan[2];
    g_hHist = CreateWindowW(L"LISTBOX", NULL,
        WS_CHILD | WS_VISIBLE | WS_VSCROLL | WS_BORDER | LBS_NOTIFY,
        12, 12, 420, 566, ph, (HMENU)(INT_PTR)IDC_HIST, g_hInst, NULL);
    g_hHistThumb = CreateWindowW(L"LDEImg", NULL, WS_CHILD | WS_VISIBLE | WS_BORDER, 446, 12, 360, 380, ph, NULL, g_hInst, NULL);
    mk(ph, L"BUTTON", S(L"🔄 刷新", L"🔄 Refresh"), BS_PUSHBUTTON, 446, 404, 150, 36, IDC_HISTREF);
    mk(ph, L"BUTTON", S(L"📂 输出文件夹", L"📂 Output folder"), BS_PUSHBUTTON, 606, 404, 190, 36, IDC_OPHOUT);

    EnumChildWindows(h, fontEnum, (LPARAM)g_fNorm);
    SendMessageW(g_hTitle, WM_SETFONT, (WPARAM)g_fTitle, TRUE);
    SendMessageW(g_hSub, WM_SETFONT, (WPARAM)g_fSmall, TRUE);
    for (int i = 0; i < 3; i++) SendMessageW(g_hTab[i], WM_SETFONT, (WPARAM)g_fBold, TRUE);
    applyTab();
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

/* ================================ WndProc ================================ */

static LRESULT CALLBACK MainProc(HWND h, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_CREATE: {
        buildUI(h);
        SetMenu(h, buildMenu());
        for (size_t i = 0; i < MODEL_COUNT; i++) { g_mpct[i] = -1; g_mstate[i] = modelReady((int)i); }
        buildList();
        refreshSelectionUI();
        fillHistory();
        return 0;
    }

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
            int li = (int)SendMessageW(g_hList, LB_GETCURSEL, 0, 0);
            if (li >= 0) { g_sel = (int)SendMessageW(g_hList, LB_GETITEMDATA, li, 0); refreshSelectionUI(); }
        }
        else if (id == IDC_LIST && code == LBN_DBLCLK) onDownload();
        else if (id == IDC_DL) onDownload();
        else if (id >= IDC_TAB0 && id <= IDC_TAB2) {
            g_tab = id - IDC_TAB0; applyTab(); if (g_tab == 2) fillHistory();
        }
        else if (id == IDC_GEN || id == IDC_REGEN) onGenerate();
        else if (id == IDC_ASPECT && code == CBN_SELCHANGE) onAspect();
        else if (id == IDC_UPLOAD) pickImage();
        else if (id == IDC_CLEARIMG) {
            g_initImg[0] = 0; setImgCtl(g_hThumb, NULL);
            SetWindowTextW(g_hImgName, S(L"未选择参考图（纯文生图）", L"No reference (text-to-image)"));
        }
        else if (id == IDC_SAVE) saveAsImage();
        else if (id == IDC_OPNOUT || id == IDC_OPHOUT) { wchar_t o[MAX_PATH]; outputDir(o, MAX_PATH); shellOpen(o); }
        else if (id == IDC_HISTREF) fillHistory();
        else if (id == IDC_HIST && code == LBN_SELCHANGE) {
            int li = (int)SendMessageW(g_hHist, LB_GETCURSEL, 0, 0);
            if (li >= 0) {
                wchar_t *p = (wchar_t *)SendMessageW(g_hHist, LB_GETITEMDATA, li, 0);
                if (p) setImgCtl(g_hHistThumb, p);
            }
        }
        else if (id == IDM_LANG_ZH || id == IDM_LANG_EN) {
            g_lang = (id == IDM_LANG_EN) ? 1 : 0; saveConfig(); applyLanguage(); buildList(); refreshMenu();
        }
        else if (id == IDM_THEME_D || id == IDM_THEME_L) {
            g_dark = (id == IDM_THEME_D); saveConfig(); switchTheme();
        }
        else if (id == IDM_UPDATE) {
            if (InterlockedExchange(&g_busy, 1) == 1) break;
            enableJobs(FALSE);
            HANDLE ht = CreateThread(NULL, 0, updateThread, NULL, 0, NULL);
            if (ht) CloseHandle(ht); else InterlockedExchange(&g_busy, 0);
        }
        else if (id == IDM_OPMDIR) { wchar_t m[MAX_PATH]; modelsDir(m, MAX_PATH); shellOpen(m); }
        else if (id == IDM_OPODIR) { wchar_t o[MAX_PATH]; outputDir(o, MAX_PATH); shellOpen(o); }
        else if (id == IDM_ABOUT) showAbout();
        return 0;
    }

    case WM_JOB_PROGRESS:
        SendMessageW(g_hProgress, PBM_SETPOS, (int)wp, 0);
        return 0;

    case WM_LIVE_PREVIEW:
        if (g_livePath[0]) {
            SetWindowLongPtrW(g_hResult, GWLP_USERDATA, (LONG_PTR)g_livePath);
            InvalidateRect(g_hResult, NULL, FALSE);
        }
        return 0;

    case WM_JOB_STATUS:
        if (lp) {
            SetWindowTextW(g_hStatus, (const wchar_t *)lp);
            GlobalFree((HGLOBAL)lp);
        }
        return 0;

    case WM_REFRESH_LIST:
        InvalidateRect(g_hList, NULL, TRUE);
        return 0;

    case WM_JOB_DONE: {
        const wchar_t *s = (const wchar_t *)lp;
        int ok = (int)wp;
        if (s) { SetWindowTextW(g_hStatus, s); if (ok) SetWindowTextW(g_hParams, s); GlobalFree((HGLOBAL)lp); }
        SendMessageW(g_hProgress, PBM_SETPOS, ok ? 100 : 0, 0);
        enableJobs(TRUE);
        InterlockedExchange(&g_busy, 0);
        if (ok) { setImgCtl(g_hResult, g_lastImage); g_tab = 1; applyTab(); fillHistory(); }
        else { /* keep prompt tab so error is visible? status on prompt tab */ }
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

    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
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

    /* Prefer models/output right next to the program (portable). Fall back to
       LocalAppData when the install dir is not writable (Program Files). */
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
    { wchar_t d[MAX_PATH]; modelsDir(d, MAX_PATH); outputDir(d, MAX_PATH); tmpDir(d, MAX_PATH);
      joinPath(d, MAX_PATH, g_baseDir, L"update"); CreateDirectoryW(d, NULL); }

    loadConfig();
    setupColors();
    registerClasses();

    WNDCLASSW wc; ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = MainProc; wc.hInstance = hInst;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = g_brBg;
    wc.lpszClassName = L"LDEMain2";
    wc.hIcon = LoadIconW(hInst, MAKEINTRESOURCEW(1));
    RegisterClassW(&wc);

    g_hMain = CreateWindowW(L"LDEMain2", APP_TITLE,
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        CW_USEDEFAULT, CW_USEDEFAULT, 1192, 760, NULL, NULL, hInst, NULL);
    ShowWindow(g_hMain, show);
    UpdateWindow(g_hMain);

    MSG m;
    while (GetMessageW(&m, NULL, 0, 0)) {
        if (!IsDialogMessageW(g_hMain, &m)) { TranslateMessage(&m); DispatchMessageW(&m); }
    }
    OleUninitialize();
    return 0;
}
