; Local Dream ET Server Windows Installer  (Copyright (C) 2026 etc)
Unicode true
ManifestDPIAware true

!include "MUI2.nsh"
!include "x64.nsh"

Name "Local Dream ET Server"
OutFile "LocalDreamET-Server-Setup-1.1.0.exe"
Unicode true
RequestExecutionLevel admin
SetCompressor /SOLID lzma
ShowInstDetails show

InstallDir "$PROGRAMFILES64\LocalDreamET-Server"
InstallDirRegKey HKLM "Software\LocalDreamET-Server" "InstallDir"

!define PRODUCT_VERSION "1.1.0"
!define PRODUCT_PUBLISHER "etc"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

; ---- Languages (must follow the MUI_[UN]PAGE macros) ----
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "SimpChinese"

Section "Local Dream ET Server" SecCore
  SectionIn RO
  SetOutPath "$INSTDIR"
  File /r "..\dist-win\LocalDreamET-Server\*.*"

  ; runtime folders the app writes into
  CreateDirectory "$INSTDIR\models"
  CreateDirectory "$INSTDIR\logs"

  ; Start menu
  CreateDirectory "$SMPROGRAMS\Local Dream ET Server"
  CreateShortcut "$SMPROGRAMS\Local Dream ET Server\Local Dream ET Server.lnk" \
    "$INSTDIR\Start-Server.bat" "$INSTDIR" "" 0
  CreateShortcut "$SMPROGRAMS\Local Dream ET Server\Uninstall.lnk" \
    "$INSTDIR\uninstall.exe"

  ; Desktop
  CreateShortcut "$DESKTOP\Local Dream ET Server.lnk" \
    "$INSTDIR\Start-Server.bat" "$INSTDIR" "" 0

  WriteRegStr HKLM "Software\LocalDreamET-Server" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalDreamET-Server" \
    "DisplayName" "Local Dream ET Server"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalDreamET-Server" \
    "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalDreamET-Server" \
    "Publisher" "${PRODUCT_PUBLISHER}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalDreamET-Server" \
    "DisplayIcon" "$INSTDIR\etserver.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalDreamET-Server" \
    "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalDreamET-Server" \
    "NoModify" 1
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalDreamET-Server" \
    "NoRepair" 1

  WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd

Section "uninstall"
  Delete "$SMPROGRAMS\Local Dream ET Server\Local Dream ET Server.lnk"
  Delete "$SMPROGRAMS\Local Dream ET Server\Uninstall.lnk"
  RMDir "$SMPROGRAMS\Local Dream ET Server"
  Delete "$DESKTOP\Local Dream ET Server.lnk"
  ; keep downloaded models/logs on uninstall unless user removed manually; remove program files
  RMDir /r "$INSTDIR\config"
  RMDir /r "$INSTDIR\docs"
  Delete "$INSTDIR\*.exe"
  Delete "$INSTDIR\*.dll"
  Delete "$INSTDIR\*.bat"
  Delete "$INSTDIR\*.txt"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR\models"
  RMDir "$INSTDIR\logs"
  RMDir "$INSTDIR"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalDreamET-Server"
  DeleteRegKey HKLM "Software\LocalDreamET-Server"
SectionEnd
