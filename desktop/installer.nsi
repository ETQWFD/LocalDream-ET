Unicode true
SetCompressor /SOLID lzma

!define APPNAME "Local Dream ET"
!define COMPANY "ET"
!define VERSION "3.0.0.0"
!define VERSIONSTR "3.0.0"

Name "${APPNAME}"
OutFile "LocalDream-ET-Setup-3.0.0.exe"
; Per-user install into a writable folder -> models live in the program's own
; "models" subfolder (the requested portable layout) and no admin / UAC is needed.
InstallDir "$LOCALAPPDATA\Programs\${APPNAME}"
InstallDirRegKey HKCU "Software\${APPNAME}" "InstallDir"
RequestExecutionLevel user
ShowInstDetails show
ShowUnInstDetails show

!include "MUI2.nsh"
!define MUI_ICON "app.ico"
!define MUI_UNICON "app.ico"
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "SimpChinese"

VIProductVersion "3.0.0.0"
VIAddVersionKey /LANG=2052 "CompanyName" "ET"
VIAddVersionKey /LANG=2052 "FileDescription" "Local Dream ET v3.0.0 Installer"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Copyright (C) 2026 ET"
VIAddVersionKey /LANG=2052 "ProductName" "Local Dream ET"
VIAddVersionKey /LANG=2052 "ProductVersion" "3.0.0.0"
VIAddVersionKey /LANG=2052 "FileVersion" "3.0.0.0"

Section "Local Dream ET" SecCore
  SectionIn RO
  SetOutPath "$INSTDIR"
  File /r "pkg\LocalDreamET\*"
  CreateDirectory "$INSTDIR\models"
  CreateDirectory "$INSTDIR\output"
  CreateDirectory "$INSTDIR\tmp"
  CreateDirectory "$INSTDIR\update"

  WriteUninstaller "$INSTDIR\uninstall.exe"

  CreateDirectory "$SMPROGRAMS\${APPNAME}"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\Local Dream ET.lnk" \
      "$INSTDIR\LocalDream-ET.exe" "" "$INSTDIR\LocalDream-ET.exe" 0
  CreateShortcut "$SMPROGRAMS\${APPNAME}\卸载 Local Dream ET.lnk" \
      "$INSTDIR\uninstall.exe" "" "$INSTDIR\uninstall.exe" 0
  CreateShortcut "$DESKTOP\Local Dream ET.lnk" \
      "$INSTDIR\LocalDream-ET.exe" "" "$INSTDIR\LocalDream-ET.exe" 0

  WriteRegStr HKCU "Software\${APPNAME}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "DisplayName" "Local Dream ET"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "DisplayIcon" '"$INSTDIR\LocalDream-ET.exe"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "Publisher" "ET"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "DisplayVersion" "${VERSIONSTR}"
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\Local Dream ET.lnk"
  RMDir /r "$SMPROGRAMS\${APPNAME}"
  ; remove program files but keep downloaded models/output unless empty
  Delete "$INSTDIR\uninstall.exe"
  Delete "$INSTDIR\*.exe"
  Delete "$INSTDIR\*.dll"
  Delete "$INSTDIR\*.txt"
  RMDir "$INSTDIR\tmp"
  RMDir "$INSTDIR\update"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}"
  DeleteRegKey HKCU "Software\${APPNAME}"
SectionEnd
