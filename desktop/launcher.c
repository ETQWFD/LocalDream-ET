/*
 * Local Dream ET - Windows desktop launcher
 * Developer (开发者): ET
 *
 * Pure Win32 (C) front-end around the official stable-diffusion.cpp CPU engine
 * (sd-cli.exe + ggml DLLs shipped alongside). Downloads SD1.5 checkpoints into
 * a local "models" folder and generates images fully offline on the PC.
 *
 * Built with x86_64-w64-mingw32-gcc (Windows 10/11 x64).
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
#include <shlobj.h>
#include <shlwapi.h>
#include <wininet.h>
#include <olectl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

/* ----------------------------- model catalog ----------------------------- */

typedef struct {
    const wchar_t *name;     /* shown in the combo */
    const char    *file;     /* local file name under models\ */
    const char    *url;      /* path after the host */
    const wchar_t *prompt;
} CatalogModel;

/* Uncensored-friendly, stable SD1.5 single-file fp16 checkpoints (with VAE),
 * mirror first, official huggingface.co as fallback. */
static const CatalogModel g_models[] = {
    { L"Absolute Reality 1.8.1（写实真人）",
      "AbsoluteReality_v181.safetensors",
      "digiplay/AbsoluteReality_v1.8.1/resolve/main/absolutereality_v181.safetensors",
      L"RAW photo, best quality, realistic, photo-realistic, masterpiece, highly detailed skin, 8k uhd, dslr, soft lighting" },
    { L"Counterfeit V2.5（动漫）",
      "Counterfeit-V2.5_fp16.safetensors",
      "gsdf/Counterfeit-V2.5/resolve/main/Counterfeit-V2.5_fp16.safetensors",
      L"masterpiece, best quality, 1girl, solo, detailed eyes, anime style" },
    { L"DreamShaper 8（全能/稳定少报错）",
      "dreamshaper_8.safetensors",
      "digiplay/DreamShaper_8/resolve/main/dreamshaper_8.safetensors",
      L"masterpiece, best quality, highly detailed, sharp focus, professional, 8k uhd" },
};
#define MODEL_COUNT (sizeof(g_models) / sizeof(g_models[0]))
#define MAX_CFG 9.0f

/* aspect presets -> multiple-of-8 sizes, longest edge 512 (SD1.5) */
static const wchar_t *g_aspects[] = { L"1:1", L"2:3", L"3:4", L"4:3", L"3:2", L"9:16", L"16:9" };

/* --------------------------------- state --------------------------------- */

static HINSTANCE g_hInst;
static HWND g_hMain, g_hModel, g_hBtnDl, g_hPrompt, g_hNeg, g_hSteps, g_hCfg,
            g_hW, g_hH, g_hSampler, g_hSeed, g_hBtnGen, g_hProgress,
            g_hStatus, g_hAspect, g_hPreview;

static volatile LONG g_busy = 0;          /* a download/generate job running */
static wchar_t g_exeDir[MAX_PATH];        /* engine + launcher install dir */
static wchar_t g_dataDir[MAX_PATH];       /* user-writable models/output dir */
static wchar_t g_lastImage[MAX_PATH] = {0};

#define WM_JOB_STATUS   (WM_APP + 1)   /* wParam=0 status str via GlobalAlloc WSTR */
#define WM_JOB_PROGRESS (WM_APP + 2)   /* wParam = percent (0..100), -1 marquee */
#define WM_JOB_DONE     (WM_APP + 3)   /* wParam=1 ok, 0 fail; lParam = msg WSTR (owned) */

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

static void modelsDir(wchar_t *out, size_t c)
{
    _snwprintf(out, c, L"%lsmodels", g_dataDir);
    CreateDirectoryW(out, NULL);
}
static void outputDir(wchar_t *out, size_t c)
{
    _snwprintf(out, c, L"%lsoutput", g_dataDir);
    CreateDirectoryW(out, NULL);
}
static void modelPath(int idx, wchar_t *out, size_t c)
{
    wchar_t md[MAX_PATH]; modelsDir(md, MAX_PATH);
    wchar_t tmp[128];
    MultiByteToWideChar(CP_UTF8, 0, g_models[idx].file, -1, tmp, 128);
    _snwprintf(out, c, L"%ls\\%ls", md, tmp);
}

/* ------------------------------- download -------------------------------- */

static BOOL httpDownload(const char *path, const wchar_t *dest,
                         void (*prog)(int, void *), void *ctx, char *err, size_t errc)
{
    static const char *hosts[] = { "https://hf-mirror.com", "https://huggingface.co" };
    for (int hi = 0; hi < 2; hi++) {
        char url[1024];
        _snprintf(url, sizeof(url), "%s/%s", hosts[hi], path);
        wchar_t wurl[1100];
        MultiByteToWideChar(CP_UTF8, 0, url, -1, wurl, 1100);

        HINTERNET hiNet = InternetOpenW(L"LocalDreamET/1.0", INTERNET_OPEN_TYPE_PRECONFIG,
                                        NULL, NULL, 0);
        if (!hiNet) continue;
        HINTERNET hUrl = InternetOpenUrlW(hiNet, wurl, NULL, 0,
            INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE |
            INTERNET_FLAG_SECURE | INTERNET_FLAG_NO_UI, 0);
        if (!hUrl) { InternetCloseHandle(hiNet); continue; }

        wchar_t code[16] = {0}; DWORD csz = sizeof(code);
        HttpQueryInfoW(hUrl, HTTP_QUERY_STATUS_CODE, code, &csz, NULL);
        if (code[0] && _wtoi(code) >= 400) {
            InternetCloseHandle(hUrl); InternetCloseHandle(hiNet); continue;
        }
        DWORD total = 0, idx = 0;
        char clen[32]; DWORD clenSz = sizeof(clen);
        if (HttpQueryInfoA(hUrl, HTTP_QUERY_CONTENT_LENGTH, clen, &clenSz, &idx))
            total = (DWORD)_strtoui64(clen, NULL, 10);

        wchar_t part[MAX_PATH]; _snwprintf(part, MAX_PATH, L"%ls.part", dest);
        HANDLE hf = CreateFileW(part, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, NULL);
        if (hf == INVALID_HANDLE_VALUE) {
            InternetCloseHandle(hUrl); InternetCloseHandle(hiNet); continue;
        }
        char buf[64 * 1024]; DWORD got = 0, readn;
        BOOL ok = TRUE;
        while (InternetReadFile(hUrl, buf, sizeof(buf), &readn) && readn > 0) {
            DWORD wr;
            if (!WriteFile(hf, buf, readn, &wr, NULL) || wr != readn) { ok = FALSE; break; }
            got += readn;
            int pct = total > 0 ? (int)((double)got * 100.0 / total) : -1;
            if (prog) prog(pct, ctx);
        }
        CloseHandle(hf);
        InternetCloseHandle(hUrl); InternetCloseHandle(hiNet);
        if (ok && got > 0) {
            MoveFileExW(part, dest, MOVEFILE_REPLACE_EXISTING);
            return TRUE;
        }
        DeleteFileW(part);
    }
    _snprintf(err, errc, "all hosts failed");
    return FALSE;
}

static void dlProgCb(int pct, void *ctx) { (void)ctx; PostMessageW(g_hMain, WM_JOB_PROGRESS, pct, 0); }

static DWORD WINAPI downloadThread(LPVOID arg)
{
    int idx = (int)(INT_PTR)arg;
    wchar_t dest[MAX_PATH]; modelPath(idx, dest, MAX_PATH);
    wchar_t st[256]; _snwprintf(st, 256, L"正在下载 %ls（约 2.1GB，首次较慢）…", g_models[idx].name);
    postStatus(st);
    char err[256] = {0};
    BOOL ok = httpDownload(g_models[idx].url, dest, dlProgCb, NULL, err, sizeof(err));
    if (ok) postDone(1, L"模型下载完成");
    else {
        wchar_t werr[300];
        MultiByteToWideChar(CP_UTF8, 0, err, -1, werr, 300);
        _snwprintf(st, 256, L"下载失败：%ls", werr);
        postDone(0, st);
    }
    return 0;
}

/* ------------------------------- generate -------------------------------- */

static void quoteArg(wchar_t *dst, size_t c, const wchar_t *src)
{
    /* surround with quotes; no embedded quotes expected in our args */
    _snwprintf(dst, c, L"\"%ls\"", src);
}

static DWORD WINAPI generateThread(LPVOID arg)
{
    (void)arg;
    int idx = (int)SendMessageW(g_hModel, CB_GETCURSEL, 0, 0);
    if (idx < 0 || (size_t)idx >= MODEL_COUNT) { postDone(0, L"请先选择模型"); return 0; }

    wchar_t mdl[MAX_PATH]; modelPath(idx, mdl, MAX_PATH);
    if (GetFileAttributesW(mdl) == INVALID_FILE_ATTRIBUTES) {
        postDone(0, L"模型未下载，请先点击“下载所选模型”");
        return 0;
    }

    wchar_t prompt[2048], neg[1024], seedtxt[64];
    GetWindowTextW(g_hPrompt, prompt, 2048);
    GetWindowTextW(g_hNeg, neg, 1024);
    GetWindowTextW(g_hSeed, seedtxt, 64);
    if (wcslen(prompt) == 0) { postDone(0, L"提示词不能为空（Prompt empty）"); return 0; }

    wchar_t wst[16], wcfg[16], ww[16], wh[16];
    GetWindowTextW(g_hSteps, wst, 16);
    GetWindowTextW(g_hCfg, wcfg, 16);
    GetWindowTextW(g_hW, ww, 16);
    GetWindowTextW(g_hH, wh, 16);
    int stepsv = _wtoi(wst); if (stepsv < 1) stepsv = 20; if (stepsv > 60) stepsv = 60;
    double cfgv = _wtof(wcfg); if (cfgv < 1) cfgv = 7; if (cfgv > MAX_CFG) cfgv = MAX_CFG;
    int wv = _wtoi(ww), hv = _wtoi(wh);
    if (wv < 128) wv = 512; if (hv < 128) hv = 512;

    wchar_t sam[32]; GetWindowTextW(g_hSampler, sam, 32);
    const wchar_t *method = L"euler_a";
    if (wcscmp(sam, L"euler") == 0) method = L"euler";
    else if (wcscmp(sam, L"dpm++2m") == 0) method = L"dpm++2m";

    SYSTEM_INFO si; GetSystemInfo(&si);
    int threads = (int)si.dwNumberOfProcessors; if (threads < 1) threads = 4;

    wchar_t outdir[MAX_PATH]; outputDir(outdir, MAX_PATH);
    SYSTEMTIME t; GetLocalTime(&t);
    wchar_t out[MAX_PATH];
    _snwprintf(out, MAX_PATH, L"%ls\\ld_%04d%02d%02d_%02d%02d%02d.png", outdir,
               t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond);

    wchar_t cli[MAX_PATH]; _snwprintf(cli, MAX_PATH, L"%ls%ls", g_exeDir, L"sd-cli.exe");
    if (GetFileAttributesW(cli) == INVALID_FILE_ATTRIBUTES) {
        postDone(0, L"未找到引擎 sd-cli.exe，请重新安装");
        return 0;
    }

    /* build command line */
    wchar_t qcli[MAX_PATH + 4], qmdl[MAX_PATH + 4], qout[MAX_PATH + 4];
    quoteArg(qcli, MAX_PATH + 4, cli);
    quoteArg(qmdl, MAX_PATH + 4, mdl);
    quoteArg(qout, MAX_PATH + 4, out);
    wchar_t cmd[6144];
    _snwprintf(cmd, 6144,
        L"%ls -m %ls -p \"%ls\" -n \"%ls\" --steps %d --cfg-scale %.1f "
        L"--width %d --height %d --sampling-method %ls -t %d --seed %ls -o %ls",
        qcli, qmdl, prompt, neg, stepsv, cfgv, wv, hv, method, threads,
        (seedtxt[0] ? seedtxt : L"-1"), qout);

    postStatus(L"正在生成（CPU 出图，请耐心等待 1～3 分钟）…");
    PostMessageW(g_hMain, WM_JOB_PROGRESS, -1, 0);

    SECURITY_ATTRIBUTES sa; sa.nLength = sizeof(sa); sa.bInheritHandle = TRUE; sa.lpSecurityDescriptor = NULL;
    HANDLE rd = NULL, wr = NULL;
    if (!CreatePipe(&rd, &wr, &sa, 0)) { postDone(0, L"创建管道失败"); return 0; }
    SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);

    STARTUPINFOW si2; ZeroMemory(&si2, sizeof(si2)); si2.cb = sizeof(si2);
    si2.dwFlags = STARTF_USESTDHANDLES;
    si2.hStdOutput = wr; si2.hStdError = wr; si2.hStdInput = NULL;
    PROCESS_INFORMATION pi; ZeroMemory(&pi, sizeof(pi));

    wchar_t dir[MAX_PATH]; _snwprintf(dir, MAX_PATH, L"%ls", g_exeDir);
    BOOL pok = CreateProcessW(cli, cmd, NULL, NULL, TRUE,
                              CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS, NULL, dir, &si2, &pi);
    CloseHandle(wr);
    if (!pok) {
        CloseHandle(rd);
        postDone(0, L"启动引擎失败（缺少运行库，请重新安装）");
        return 0;
    }

    char obuf[8192]; DWORD nread;
    char tail[600]; tail[0] = 0; size_t tailLen = 0;
    while (ReadFile(rd, obuf, sizeof(obuf) - 1, &nread, NULL) && nread > 0) {
        obuf[nread] = 0;
        size_t add = nread;
        if (tailLen + add >= sizeof(tail)) add = sizeof(tail) - 1 - tailLen;
        memcpy(tail + tailLen, obuf, add); tailLen += add; tail[tailLen] = 0;
        if (tailLen >= sizeof(tail) - 1) {
            memmove(tail, tail + 200, tailLen - 200); tailLen -= 200;
        }
        /* crude progress: look for n/20 style tokens */
        for (DWORD i = 0; i + 2 < nread; i++) {
            if (obuf[i] >= '1' && obuf[i] <= '9') {
                int v = obuf[i] - '0';
                if (v >= 1 && v <= 9) PostMessageW(g_hMain, WM_JOB_PROGRESS, v * 10, 0);
            }
        }
    }
    CloseHandle(rd);
    WaitForSingleObject(pi.hProcess, 30 * 60 * 1000);
    DWORD ec = 1; GetExitCodeProcess(pi.hProcess, &ec);
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);

    if (ec == 0 && GetFileAttributesW(out) != INVALID_FILE_ATTRIBUTES) {
        wcsncpy(g_lastImage, out, MAX_PATH - 1);
        postDone(1, L"生成完成");
    } else {
        wchar_t wtail[620];
        MultiByteToWideChar(CP_UTF8, 0, tail, -1, wtail, 620);
        wchar_t msg[900];
        _snwprintf(msg, 900, L"生成失败（退出码 %lu）：%ls", ec, wtail);
        postDone(0, msg);
    }
    return 0;
}

/* ------------------------------ preview PNG ------------------------------ */

static void drawPreview(HWND hwnd, HDC hdc)
{
    RECT rc; GetClientRect(hwnd, &rc);
    HBRUSH bg = CreateSolidBrush(RGB(24, 24, 28));
    FillRect(hdc, &rc, bg); DeleteObject(bg);

    if (!g_lastImage[0] || GetFileAttributesW(g_lastImage) == INVALID_FILE_ATTRIBUTES) {
        SetBkMode(hdc, TRANSPARENT); SetTextColor(hdc, RGB(170, 170, 178));
        HFONT f = CreateFontW(16, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                              0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
        HFONT old = SelectObject(hdc, f);
        const wchar_t *hint = L"生成结果显示在这里";
        SIZE sz; GetTextExtentPoint32W(hdc, hint, (int)wcslen(hint), &sz);
        TextOutW(hdc, (rc.right - sz.cx) / 2, (rc.bottom - sz.cy) / 2, hint, (int)wcslen(hint));
        SelectObject(hdc, old); DeleteObject(f);
        return;
    }

    /* load PNG through IPicture */
    HANDLE hf = CreateFileW(g_lastImage, GENERIC_READ, FILE_SHARE_READ, NULL,
                            OPEN_EXISTING, 0, NULL);
    if (hf == INVALID_HANDLE_VALUE) return;
    DWORD szHigh = 0, szLow = GetFileSize(hf, &szHigh);
    ULONGLONG sz64 = ((ULONGLONG)szHigh << 32) | szLow;
    ULONG fsize = (ULONG)sz64;
    HGLOBAL hg = GlobalAlloc(GMEM_MOVEABLE, fsize);
    void *p = GlobalLock(hg); DWORD rd;
    ReadFile(hf, p, fsize, &rd, NULL); GlobalUnlock(hg); CloseHandle(hf);

    IStream *stream = NULL;
    if (SUCCEEDED(CreateStreamOnHGlobal(hg, FALSE, &stream)) && stream) {
        IPicture *pic = NULL;
        if (SUCCEEDED(OleLoadPicture(stream, fsize, FALSE, &IID_IPicture, (void **)&pic)) && pic) {
            LONG pw = 0, ph = 0;
            pic->lpVtbl->get_Width(pic, &pw);
            pic->lpVtbl->get_Height(pic, &ph);
            HDC scr = GetDC(NULL);
            int dpx = GetDeviceCaps(scr, LOGPIXELSX);
            int dpy = GetDeviceCaps(scr, LOGPIXELSY);
            ReleaseDC(NULL, scr);
            int iw = MulDiv(pw, dpx, 2540);   /* HIMETRIC -> pixels */
            int ih = MulDiv(ph, dpy, 2540);
            if (iw < 1) iw = rc.right; if (ih < 1) ih = rc.bottom;
            double s = (double)(rc.right - 12) / iw;
            if ((double)(rc.bottom - 12) / ih < s) s = (double)(rc.bottom - 12) / ih;
            int dw = (int)(iw * s), dh = (int)(ih * s);
            int x = (rc.right - dw) / 2, y = (rc.bottom - dh) / 2;
            RECT rcPic; SetRect(&rcPic, x, y, x + dw, y + dh);
            /* IPicture::Render uses HIMETRIC; use himetric extents */
            pic->lpVtbl->Render(pic, hdc, x, y, dw, dh, 0, ph, pw, -ph, &rcPic);
            pic->lpVtbl->Release(pic);
        }
        stream->lpVtbl->Release(stream);
    }
    GlobalFree(hg);
}

static LRESULT CALLBACK PreviewProc(HWND h, UINT m, WPARAM w, LPARAM l)
{
    if (m == WM_PAINT) {
        PAINTSTRUCT ps; HDC hdc = BeginPaint(h, &ps);
        drawPreview(h, hdc);
        EndPaint(h, &ps);
        return 0;
    }
    if (m == WM_ERASEBKGND) return 1;
    return DefWindowProcW(h, m, w, l);
}

/* -------------------------------- layout --------------------------------- */

static HWND mkLabel(HWND parent, const wchar_t *t, int x, int y, int w, int h)
{
    return CreateWindowW(L"STATIC", t, WS_CHILD | WS_VISIBLE, x, y, w, h,
                         parent, NULL, g_hInst, NULL);
}
static HWND mkEdit(HWND parent, int x, int y, int w, int h, DWORD extra, const wchar_t *txt)
{
    return CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", txt,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | extra, x, y, w, h, parent, NULL, g_hInst, NULL);
}

static void setAspectWH(void)
{
    int ai = (int)SendMessageW(g_hAspect, CB_GETCURSEL, 0, 0);
    if (ai < 0) ai = 0;
    const wchar_t *a = g_aspects[ai];
    int rw = 1, rh = 1;
    if (swscanf(a, L"%d:%d", &rw, &rh) != 2) { rw = rh = 1; }
    int edge = 512, w, h;
    if (rw >= rh) { w = edge; h = edge * rh / rw; }
    else { h = edge; w = edge * rw / rh; }
    /* snap to a multiple of 8, clamp into 128..512 (SD1.5) */
    #define ALIGN8(v) do { (v) = (((v) + 4) / 8) * 8; if ((v) < 128) (v) = 128; if ((v) > 512) (v) = 512; } while (0)
    ALIGN8(w); ALIGN8(h);
    wchar_t b[16];
    _snwprintf(b, 16, L"%d", w); SetWindowTextW(g_hW, b);
    _snwprintf(b, 16, L"%d", h); SetWindowTextW(g_hH, b);
}

static BOOL CALLBACK setChildFont(HWND cw, LPARAM lp)
{
    SendMessageW(cw, WM_SETFONT, (WPARAM)lp, TRUE);
    return TRUE;
}

/* ------------------------------- window ---------------------------------- */

#define IDC_MODEL   1001
#define IDC_DL      1002
#define IDC_GEN     1003
#define IDC_ASPECT  1004

static void enableControls(BOOL on)
{
    EnableWindow(g_hModel, on); EnableWindow(g_hBtnDl, on);
    EnableWindow(g_hBtnGen, on); EnableWindow(g_hPrompt, on);
    EnableWindow(g_hNeg, on); EnableWindow(g_hSteps, on);
    EnableWindow(g_hCfg, on); EnableWindow(g_hW, on); EnableWindow(g_hH, on);
    EnableWindow(g_hSampler, on); EnableWindow(g_hSeed, on);
    EnableWindow(g_hAspect, on);
}

static void onDownload(void)
{
    if (InterlockedExchange(&g_busy, 1) == 1) return;
    int idx = (int)SendMessageW(g_hModel, CB_GETCURSEL, 0, 0);
    if (idx < 0) { g_busy = 0; return; }
    wchar_t p[MAX_PATH]; modelPath(idx, p, MAX_PATH);
    if (GetFileAttributesW(p) != INVALID_FILE_ATTRIBUTES) {
        SetWindowTextW(g_hStatus, L"该模型已下载，可直接生成");
        g_busy = 0; return;
    }
    enableControls(FALSE);
    SendMessageW(g_hProgress, PBM_SETPOS, 0, 0);
    HANDLE h = CreateThread(NULL, 0, downloadThread, (LPVOID)(INT_PTR)idx, 0, NULL);
    if (h) CloseHandle(h); else { g_busy = 0; enableControls(TRUE); }
}

static void onGenerate(void)
{
    if (InterlockedExchange(&g_busy, 1) == 1) return;
    enableControls(FALSE);
    SendMessageW(g_hProgress, PBM_SETPOS, 0, 0);
    HANDLE h = CreateThread(NULL, 0, generateThread, NULL, 0, NULL);
    if (h) CloseHandle(h); else { g_busy = 0; enableControls(TRUE); }
}

static LRESULT CALLBACK MainProc(HWND h, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_CREATE: {
        HFONT f = CreateFontW(16, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                              0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
        int y = 12;
        mkLabel(h, L"模型", 14, y, 60, 22);
        g_hModel = CreateWindowW(L"COMBOBOX", NULL,
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | CBS_DROPDOWNLIST | WS_VSCROLL,
            78, y - 2, 300, 220, h, (HMENU)IDC_MODEL, g_hInst, NULL);
        for (size_t i = 0; i < MODEL_COUNT; i++)
            SendMessageW(g_hModel, CB_ADDSTRING, 0, (LPARAM)g_models[i].name);
        SendMessageW(g_hModel, CB_SETCURSEL, 0, 0);
        g_hBtnDl = CreateWindowW(L"BUTTON", L"下载所选模型",
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
            392, y - 3, 118, 28, h, (HMENU)IDC_DL, g_hInst, NULL);
        y += 38;

        mkLabel(h, L"提示词", 14, y, 70, 20);
        g_hPrompt = mkEdit(h, 78, y - 3, 432, 70, ES_MULTILINE | ES_WANTRETURN | ES_AUTOVSCROLL | WS_VSCROLL,
                           g_models[0].prompt);
        y += 80;
        mkLabel(h, L"负面提示", 14, y, 70, 20);
        g_hNeg = mkEdit(h, 78, y - 3, 432, 56, ES_MULTILINE | ES_AUTOVSCROLL | WS_VSCROLL,
                        L"lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, worst quality, low quality, jpeg artifacts, signature, watermark, blurry");
        y += 66;

        mkLabel(h, L"步数", 14, y + 4, 50, 20);
        g_hSteps = mkEdit(h, 60, y, 50, 24, ES_NUMBER, L"22");
        mkLabel(h, L"CFG(1-9)", 120, y + 4, 70, 20);
        g_hCfg = mkEdit(h, 190, y, 46, 24, 0, L"7");
        mkLabel(h, L"比例", 248, y + 4, 40, 20);
        g_hAspect = CreateWindowW(L"COMBOBOX", NULL,
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | CBS_DROPDOWNLIST,
            288, y - 2, 78, 200, h, (HMENU)IDC_ASPECT, g_hInst, NULL);
        for (size_t i = 0; i < sizeof(g_aspects) / sizeof(g_aspects[0]); i++)
            SendMessageW(g_hAspect, CB_ADDSTRING, 0, (LPARAM)g_aspects[i]);
        SendMessageW(g_hAspect, CB_SETCURSEL, 0, 0);
        g_hW = mkEdit(h, 374, y, 44, 24, ES_NUMBER, L"512");
        mkLabel(h, L"×", 421, y + 4, 12, 20), g_hH = mkEdit(h, 434, y, 44, 24, ES_NUMBER, L"512");
        y += 36;

        mkLabel(h, L"采样器", 14, y + 4, 50, 20);
        g_hSampler = CreateWindowW(L"COMBOBOX", NULL,
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | CBS_DROPDOWNLIST,
            60, y - 2, 120, 120, h, NULL, g_hInst, NULL);
        SendMessageW(g_hSampler, CB_ADDSTRING, 0, (LPARAM)L"euler_a");
        SendMessageW(g_hSampler, CB_ADDSTRING, 0, (LPARAM)L"dpm++2m");
        SendMessageW(g_hSampler, CB_ADDSTRING, 0, (LPARAM)L"euler");
        SendMessageW(g_hSampler, CB_SETCURSEL, 0, 0);
        mkLabel(h, L"种子(-1随机)", 196, y + 4, 100, 20);
        g_hSeed = mkEdit(h, 296, y, 110, 24, 0, L"-1");
        g_hBtnGen = CreateWindowW(L"BUTTON", L"生成图像",
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_DEFPUSHBUTTON,
            416, y - 2, 94, 30, h, (HMENU)IDC_GEN, g_hInst, NULL);
        y += 42;

        g_hProgress = CreateWindowExW(0, PROGRESS_CLASSW, NULL,
            WS_CHILD | WS_VISIBLE | PBS_SMOOTH, 14, y, 496, 16, h, NULL, g_hInst, NULL);
        SendMessageW(g_hProgress, PBM_SETRANGE32, 0, 100);
        y += 24;
        g_hStatus = mkLabel(h, L"就绪。请选择模型并下载后生成。", 14, y, 496, 20);
        y += 26;

        WNDCLASSW pwc; ZeroMemory(&pwc, sizeof(pwc));
        pwc.lpfnWndProc = PreviewProc; pwc.hInstance = g_hInst;
        pwc.lpszClassName = L"LDEPreview"; pwc.hbrBackground = NULL;
        RegisterClassW(&pwc);
        g_hPreview = CreateWindowW(L"LDEPreview", NULL, WS_CHILD | WS_VISIBLE,
            14, y, 496, 360, h, NULL, g_hInst, NULL);

        EnumChildWindows(h, setChildFont, (LPARAM)f);

        SendMessageW(h, WM_SETFONT, (WPARAM)f, TRUE);
        return 0;
    }

    case WM_COMMAND:
        if (LOWORD(wp) == IDC_DL) onDownload();
        else if (LOWORD(wp) == IDC_GEN) onGenerate();
        else if (LOWORD(wp) == IDC_ASPECT && HIWORD(wp) == CBN_SELCHANGE) setAspectWH();
        else if (LOWORD(wp) == IDC_MODEL && HIWORD(wp) == CBN_SELCHANGE) {
            int idx = (int)SendMessageW(g_hModel, CB_GETCURSEL, 0, 0);
            if (idx >= 0 && (size_t)idx < MODEL_COUNT)
                SetWindowTextW(g_hPrompt, g_models[idx].prompt);
        }
        return 0;

    case WM_JOB_PROGRESS: {
        int v = (int)wp;
        if (v < 0) {
            /* marquee style: pulse */
            SendMessageW(g_hProgress, PBM_SETPOS, 50, 0);
        } else {
            SendMessageW(g_hProgress, PBM_SETPOS, v, 0);
        }
        return 0;
    }
    case WM_JOB_STATUS:
        if (lp) { SetWindowTextW(g_hStatus, (const wchar_t *)lp); GlobalFree((HGLOBAL)lp); }
        return 0;
    case WM_JOB_DONE: {
        const wchar_t *s = (const wchar_t *)lp;
        if (s) { SetWindowTextW(g_hStatus, s); GlobalFree((HGLOBAL)lp); }
        SendMessageW(g_hProgress, PBM_SETPOS, wp ? 100 : 0, 0);
        enableControls(TRUE);
        InterlockedExchange(&g_busy, 0);
        if (wp) InvalidateRect(g_hPreview, NULL, TRUE);
        return 0;
    }
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLOREDIT: {
        HDC dc = (HDC)wp;
        SetTextColor(dc, RGB(20, 20, 24));
        SetBkMode(dc, TRANSPARENT);
        return (LRESULT)GetStockObject(WHITE_BRUSH);
    }
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR cmd, int show)
{
    (void)hPrev; (void)cmd;
    g_hInst = hInst;
    OleInitialize(NULL);

    INITCOMMONCONTROLSEX icc = { sizeof(icc), ICC_PROGRESS_CLASS | ICC_STANDARD_CLASSES };
    InitCommonControlsEx(&icc);

    GetModuleFileNameW(NULL, g_exeDir, MAX_PATH);
    wchar_t *slash = wcsrchr(g_exeDir, L'\\');
    if (slash) slash[1] = 0;

    /* Models/output go to the user-writable LocalAppData so the program also
     * works when installed under Program Files. */
    wchar_t local[MAX_PATH] = {0};
    if (SHGetFolderPathW(NULL, CSIDL_LOCAL_APPDATA, NULL, 0, local) == S_OK) {
        _snwprintf(g_dataDir, MAX_PATH, L"%ls\\LocalDreamET\\", local);
    } else {
        _snwprintf(g_dataDir, MAX_PATH, L"%ls", g_exeDir);
    }
    CreateDirectoryW(g_dataDir, NULL);
    {
        wchar_t md[MAX_PATH], od[MAX_PATH];
        modelsDir(md, MAX_PATH); outputDir(od, MAX_PATH);
    }

    WNDCLASSW wc; ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = MainProc;
    wc.hInstance = hInst;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW);
    wc.lpszClassName = L"LDEMain";
    wc.hIcon = LoadIconW(hInst, MAKEINTRESOURCEW(1));
    RegisterClassW(&wc);

    g_hMain = CreateWindowW(L"LDEMain", L"Local Dream ET  电脑版  ·  开发者 ET",
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        CW_USEDEFAULT, CW_USEDEFAULT, 544, 800, NULL, NULL, hInst, NULL);
    ShowWindow(g_hMain, show);
    UpdateWindow(g_hMain);

    MSG m;
    while (GetMessageW(&m, NULL, 0, 0)) {
        if (!IsDialogMessageW(g_hMain, &m)) {
            TranslateMessage(&m);
            DispatchMessageW(&m);
        }
    }
    OleUninitialize();
    return 0;
}
