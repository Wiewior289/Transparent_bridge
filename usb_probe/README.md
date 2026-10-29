# USB Probe (Android USB Host API)

Minimalna, niezależna aplikacja diagnostyczna do eksperymentu z własnością interfejsu USB Ethernet na Androidzie bez roota.

## Cel

Izolować zachowanie Androidowego USB Host API bez Tailscale, VPN/TUN, Go, routingu i libusb.

## Etap 0/1

1. Wykrywa urządzenia USB i żąda zgody przez `UsbManager`.
2. Otwiera urządzenie przez `UsbDeviceConnection`.
3. Loguje konfiguracje, interfejsy, alternate settings i endpointy.
4. Wybiera wyłącznie interfejs zawierający endpointy bulk IN oraz bulk OUT.
5. Próbuje `claimInterface(interface, true)`.
6. Jeżeli interfejs ma alternate setting != 0, próbuje `setInterface()`.
7. Trzyma połączenie i interfejs zajęte aż do ręcznego zwolnienia lub zamknięcia aplikacji.
8. Loguje stan widocznych interfejsów sieciowych JVM oraz zdarzenia USB attach/detach.

**Nie wysyła ani nie odbiera danych USB.** To celowe: najpierw sprawdzamy stabilność własności interfejsu, dopiero później dodamy test transferu.

## Uruchomienie

Z katalogu głównego repozytorium:

```bash
./gradlew -p usb_probe assembleDebug
```

APK:
`usb_probe/app/build/outputs/apk/debug/app-debug.apk`

Jeżeli wrapper nie obsłuży parametru `-p` w danym środowisku, przejdź do katalogu `usb_probe` i uruchom wrapper z katalogu nadrzędnego z `-p usb_probe`.

## Procedura pomiarowa

1. Odłącz adapter.
2. Uruchom aplikację i naciśnij **Refresh USB**.
3. Podłącz adapter, wybierz urządzenie i udziel zgody.
4. Zapisz log bazowy: czy Android utworzył `eth0` / `usb0`.
5. Naciśnij **Claim data interface (force=true)**.
6. Nie zamykaj aplikacji i nie zwalniaj interfejsu przez 60–120 sekund.
7. Obserwuj, czy interfejs sieciowy znika lub pojawia się ponownie. Równolegle zbierz `logcat` i, jeśli dostępny, `dmesg`.
8. Naciśnij **Release** i sprawdź, czy systemowy interfejs wraca.

Zapisuj dokładny model adaptera, Android build, godzinę zdarzenia oraz logi aplikacji i systemu.

## Ważne ograniczenia

- Sukces `claimInterface()` nie dowodzi działania transmisji Ethernet ani trwałego odłączenia sterownika jądra.
- Aplikacja nie używa ukrytych API, roota, sysfs ani `/dev/bus/usb`.
- Nie zmienia konfiguracji IP, routingu ani firewall.
- Ten etap celowo nie integruje libusb. Integracja FD/libusb będzie osobnym eksperymentem po zebraniu wyniku bazowego.
