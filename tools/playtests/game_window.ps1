param([Parameter(Mandatory=$true)][int]$GameProcessId,
      [Parameter(Mandatory=$true)][string]$OutputPath,
      [string]$Keys='')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class GameWindow {
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr w,out Rect rect);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr w);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr w,int command);
}
'@
$process=Get-Process -Id $GameProcessId
if ($process.ProcessName -notin @('java','javaw') -or !$process.MainWindowHandle) { throw 'Expected a Java game window' }
$window=$process.MainWindowHandle
[GameWindow]::ShowWindow($window,9) | Out-Null
[GameWindow]::SetForegroundWindow($window) | Out-Null
Start-Sleep -Milliseconds 400
if ([GameWindow]::GetForegroundWindow() -ne $window) { throw 'Game is not foreground; refusing input or screenshot' }
if ($Keys) {
    $shell=New-Object -ComObject WScript.Shell
    $shell.SendKeys($Keys)
    Start-Sleep -Milliseconds 700
}
$rect=New-Object GameWindow+Rect
if (![GameWindow]::GetWindowRect($window,[ref]$rect)) { throw 'No game rectangle' }
$bitmap=New-Object Drawing.Bitmap ($rect.Right-$rect.Left),($rect.Bottom-$rect.Top)
$graphics=[Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.CopyFromScreen($rect.Left,$rect.Top,0,0,$bitmap.Size)
    $bitmap.Save([IO.Path]::GetFullPath($OutputPath),[Drawing.Imaging.ImageFormat]::Png)
} finally { $graphics.Dispose(); $bitmap.Dispose() }
Write-Output ([IO.Path]::GetFullPath($OutputPath))
