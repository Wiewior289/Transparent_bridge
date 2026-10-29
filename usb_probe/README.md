# USB Probe (Android USB Host API)

Minimalna, niezależna aplikacja diagnostyczna do eksperymentu z własnością interfejsu USB Ethernet na Androidzie bez roota.

## Cel

Izolować zachowanie Androidowego USB Host API bez Tailscale, VPN/TUN, Go, routingu i libusb.

## Etap 0/1

1. Wykrywa urządzenia USB i żąda zgody przez `UsbManager`.
2. Otwiera urządzenie przez `UsbDeviceConnection`.
3. Loguje konfiguracje, interfejsy, alternate settings i endpointy.
4. Pokazuje osobny wybór konkretnego deskryptora interfejsu: indeks z listy Android API, ID, alternate setting, klasę i endpointy.
5. Próbuje `claimInterface(wybranyInterfejs, true)` bez automatycznego wybierania pierwszego pasującego interfejsu.
6. Jeżeli wybrany interfejs ma alternate setting != 0, próbuje `setInterface()`.
7. Trzyma połączenie i interfejs zajęte aż do ręcznego zwolnienia lub zamknięcia aplikacji.
8. Loguje stan widocznych interfejsów JVM oraz zdarzenia USB attach/detach.

**Nie wysyła ani nie odbiera danych USB.** To celowe: ten etap rozdziela wybór deskryptora i wynik claim od późniejszego testu transferu. Nie wolno interpretować `CLAIM result=true` jako dowodu działającego Ethernetu.

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
5. Wybierz konkretny interfejs z drugiej listy. Najpierw testuj deskryptor z bulk IN i bulk OUT, ale osobno porównaj inne deskryptory o tym samym ID i różnym alternate setting.
6. Naciśnij **Claim SELECTED interface (force=true)**.
7. Nie zamykaj aplikacji i nie zwalniaj interfejsu przez 60–120 sekund.
8. Obserwuj, czy interfejs sieciowy znika lub pojawia się ponownie. Równolegle zbierz `logcat` i, jeśli dostępny, `dmesg`.
9. Naciśnij **Release** i sprawdź, czy systemowy interfejs wraca.

Zapisuj dokładny model adaptera, Android build, godzinę zdarzenia oraz logi aplikacji i systemu.

## Ważne ograniczenia

- Sukces `claimInterface()` nie dowodzi działania transmisji Ethernet ani trwałego odłączenia sterownika jądra.
- Aplikacja nie używa ukrytych API, roota, sysfs ani `/dev/bus/usb`.
- Nie zmienia konfiguracji IP, routingu ani firewall.
- Ten etap celowo nie integruje libusb. Integracja FD/libusb będzie osobnym eksperymentem po zebraniu wyniku bazowego.


## Wyniki porównawcze dotychczasowych prób (29.10.2026)

### WCH USB 10/100 LAN, VID:PID 1A86:5394, przez hub

- Android widzi 2 konfiguracje i 4 deskryptory interfejsów w spłaszczonej liście API.
- W logu testu aplikacja automatycznie wybrała pierwszy deskryptor z bulk IN/OUT: `index=0, id=0, alt=0, class=0xFF`, endpointy IN `0x82`, OUT `0x03`.
- `claimInterface(..., true)` zwróciło `true`.
- W dmesg w zbliżonym czasie pojawiły się `CLAIMINTERFACE error ret=-16` oraz wyrejestrowanie `cdc_ether`/ `eth0`; później `eth0` został zarejestrowany ponownie. `-16` oznacza `EBUSY`, ale sam wpis nie identyfikuje procesu, który go wywołał.
- Deskryptor `index=3, id=1, alt=1, class=0x0A` ma bulk IN `0x82` i OUT `0x03`. W nowej wersji można wybrać go jawnie, zamiast zakładać, że pierwszy interfejs z bulk IN/OUT jest właściwy.

### Realtek RTL8156, VID:PID 0BDA:8156

- W poprzednim teście `claimInterface(..., true)` również zwróciło `true` dla `id=0, alt=0`.
- Odpowiadające mu endpointy raportowane w tym teście: bulk IN `0x81`, bulk OUT `0x02`, dodatkowo interrupt IN `0x83`.
- To potwierdza tylko powodzenie claim. Nie mamy jeszcze porównywalnego testu transferu ani pełnego pomiaru stabilności sterownika dla obu adapterów.

**Nie wybieramy zwycięzcy na podstawie samego claim.** Następny etap to powtarzalny test wybranych deskryptorów oraz obserwacja, czy sterownik jądra znika, pozostaje odłączony i wraca po Release. Transfery bulk dodamy dopiero po ustaleniu, który deskryptor rzeczywiście jest właściwy.
