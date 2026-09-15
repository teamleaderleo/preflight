; The bundled engine is replaceable application payload. Tauri's same-version
; reinstall and /UPDATE paths overlay resources without uninstalling obsolete
; files, which can leave a mixed Java runtime. Never touch Preflight user data.
!include "LogicLib.nsh"

; Refuse junctions/symlinks before recursive removal, including nested entries.
; All registers used by this recursive walk are saved on the NSIS stack.
Function PreflightCheckEngineTree
  Exch $0
  Push $1
  Push $2
  Push $3
  Push $4
  System::Call 'kernel32::GetFileAttributesW(w r0) i .r1'
  ${If} $1 == -1
    SetErrorLevel 2
    Abort "Could not inspect the previous Preflight engine."
  ${EndIf}
  IntOp $2 $1 & 0x400
  ${If} $2 != 0
    SetErrorLevel 2
    Abort "The previous Preflight engine contains a link. Move it aside before installing."
  ${EndIf}
  IntOp $2 $1 & 0x10
  ${If} $2 != 0
    FindFirst $3 $4 "$0\*"
    ${DoWhile} $4 != ""
      ${If} $4 != "."
      ${AndIf} $4 != ".."
        Push "$0\$4"
        Call PreflightCheckEngineTree
      ${EndIf}
      FindNext $3 $4
    ${Loop}
    FindClose $3
  ${EndIf}
  Pop $4
  Pop $3
  Pop $2
  Pop $1
  Pop $0
FunctionEnd

!macro NSIS_HOOK_PREINSTALL
  ; The upstream process check otherwise runs AFTER this hook.
  !insertmacro CheckIfAppIsRunning "${MAINBINARYNAME}.exe" "${PRODUCTNAME}"
  IfFileExists "$INSTDIR\engine" 0 preflight_engine_done
  IfFileExists "$INSTDIR\engine\bundle.json" 0 preflight_engine_unknown
  IfFileExists "$INSTDIR\engine\preflight.jar" 0 preflight_engine_unknown
  Push "$INSTDIR\engine"
  Call PreflightCheckEngineTree
  ClearErrors
  RMDir /r "$INSTDIR\engine"
  ${If} ${Errors}
    SetErrorLevel 2
    Abort "Could not replace the Preflight engine. Close Preflight and try again."
  ${EndIf}
  Goto preflight_engine_done
  preflight_engine_unknown:
    SetErrorLevel 2
    Abort "The engine directory is not a recognized Preflight installation. Move it aside before installing."
  preflight_engine_done:
!macroend
