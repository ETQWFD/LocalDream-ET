Unicode true
SetCompressor /SOLID lzma

!define APPNAME "Local Dream ET"
!define COMPANY "ET"
!define VERSION "1.0.0.0"

Name "${APPNAME}"
OutFile "LocalDream-ET-Setup-1.0.0.exe"
InstallDir "$PROGRAMFILES64\${APPNAME}"
InstallDirRegKey HKLM "Software\${APPNAME}" "InstallDir"
RequestExecutionLevel admin
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

VIProductVersion "1.0.0.0"
VIAddVersionKey /LANG=2052 "CompanyName" "ET"
VIAddVersionKey /LANG=2052 "FileDescription" "Local Dream ET Installer"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Copyright (C) 2026 ET"
VIAddVersionKey /LANG=2052 "ProductName" "Local Dream ET"
VIAddVersionKey /LANG=2052 "ProductVersion" "1.0.0.0"

Section "Local Dream ET" SecCore
  SectionIn RO
  SetOutPath "$INSTDIR"
  File /r "pkg\LocalDreamET\*"

  SetOutPath "$INSTDIR"
  WriteUninstaller "$INSTDIR\uninstall.exe"

  CreateDirectory "$SMPROGRAMS\${APPNAME}"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\Local Dream ET.lnk" \
      "$INSTDIR\LocalDream-ET.exe" "" "$INSTDIR\LocalDream-ET.exe" 0
  CreateShortcut "$SMPROGRAMS\${APPNAME}\卸载 Local Dream ET.lnk" \
      "$INSTDIR\uninstall.exe" "" "$INSTDIR\uninstall.exe" 0
  CreateShortcut "$DESKTOP\Local Dream ET.lnk" \
      "$INSTDIR\LocalDream-ET.exe" "" "$INSTDIR\LocalDream-ET.exe" 0

  WriteRegStr HKLM "Software\${APPNAME}" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "DisplayName" "Local Dream ET"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "DisplayIcon" '"$INSTDIR\LocalDream-ET.exe"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "Publisher" "ET"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}" \
      "DisplayVersion" "${VERSION}"
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\Local Dream ET.lnk"
  RMDir /r "$SMPROGRAMS\${APPNAME}"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}"
  DeleteRegKey HKLM "Software\${APPNAME}"
SectionEnd
