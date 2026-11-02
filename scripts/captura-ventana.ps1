# Captura la ventana del cliente de Minecraft de PRUEBA (aunque esté detrás de otras) a un PNG.
# Uso (PowerShell):  .\scripts\captura-ventana.ps1 -salida capturas\01-anillo.png  [-jugador Lidia]
param([Parameter(Mandatory)][string]$salida, [string]$jugador = "LONIUX")
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public class VentanaMc {
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out R r);
  public struct R { public int L, T, Ri, B; }
}
"@
$patron = "cliente-prueba-$jugador"
# Solo el cliente de prueba (su carpeta de juego es cliente-prueba*), nunca el Minecraft propio del usuario.
$p = Get-Process java, javaw -ErrorAction SilentlyContinue | Where-Object {
  $_.MainWindowTitle -like "Minecraft*" -and
  (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine -match $patron } |
  Sort-Object StartTime -Descending | Select-Object -First 1
if (-not $p) { throw "No encontré la ventana de Minecraft" }
$r = New-Object VentanaMc+R
[VentanaMc]::GetClientRect($p.MainWindowHandle, [ref]$r) | Out-Null
$bmp = New-Object System.Drawing.Bitmap ($r.Ri - $r.L), ($r.B - $r.T)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $g.GetHdc()
[VentanaMc]::PrintWindow($p.MainWindowHandle, $dc, 3) | Out-Null   # 3 = solo cliente + contenido acelerado
$g.ReleaseHdc($dc)
$bmp.Save((Join-Path (Get-Location) $salida))
"captura: $salida ($($bmp.Width)x$($bmp.Height))"
