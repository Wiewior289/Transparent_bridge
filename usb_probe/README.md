# USB Probe (Android USB Host API)

Minimalna, niezależna aplikacja diagnostyczna do eksperymentu z własnością interfejsu USB Ethernet na Androidzie bez roota.

## Cel

Izolować zachowanie Androidowego USB Host API bez Tailscale, VPN/TUN, Go, routingu i libusb.

## Etap 0/1

1. Wykrywa urządzenia USB i żąda zgody przez `UsbManager`.
2. Otwiera urządzenie przez `UsbDeviceConnection`.
3. Loguje każdą konfigurację osobno: ID, nazwę, liczbę interfejsów, max power, self-powered, remote wakeup, a następnie interfejsy i endpointy. Dodatkowo loguje spłaszczoną listę `UsbDevice` używaną przez selektor.
4. Pokazuje osobny wybór konkretnego deskryptora interfejsu: indeks z listy Android API, ID, alternate setting, klasę i endpointy.
5. Próbuje `claimInterface(wybranyInterfejs, true)` bez automatycznego wybierania pierwszego pasującego interfejsu.
6. Oddziela `claimInterface()` od `setInterface()`: po Claim nie wykonuje automatycznie `setInterface()`.
7. Dodaje jednorazowy `bulkTransfer()` IN i jednorazowy OUT w wątku roboczym, z timeoutem i logiem czasu/wyniku. Endpoint wybierany jest z przejętego deskryptora, bez hardkodowania adresu endpointu.
8. Ramka OUT jest wysyłana dopiero po potwierdzeniu w oknie dialogowym; to broadcast i może być widoczny dla innych urządzeń w segmencie.
9. Trzyma połączenie i interfejs zajęte aż do ręcznego zwolnienia lub zamknięcia aplikacji.
10. Loguje stan widocznych interfejsów JVM oraz zdarzenia USB attach/detach.

**Etap 2 dodaje kontrolowane testy transferu bulk.** `Bulk IN` wykonuje pojedynczy odczyt z pierwszego endpointu bulk IN przejętego deskryptora (bufor 4096 B, timeout 1500 ms) w wątku roboczym. `Bulk OUT` po osobnym potwierdzeniu wysyła jedną 60-bajtową ramkę Ethernet broadcast z EtherType `0x88B5`, bez IP/ARP/DHCP. Wysyłaj ją wyłącznie w odizolowanym segmencie testowym. Wynik OUT równy długości ramki oznacza, że Android USB Host API przyjęło bufor, nie że odbiornik ją odebrał. Odczyt `-1` nie rozróżnia timeoutu i błędu USB, bo publiczne API nie udostępnia tu errno.

## Uruchomienie

Z katalogu głównego repozytorium, przy zainstalowanym Gradle 8.11.1:

```bash
gradle -p usb_probe assembleDebug --no-configuration-cache
```

W tym repozytorium nie ma skryptu `gradlew`, mimo obecności `gradle/wrapper/gradle-wrapper.properties`. Nie uruchamiaj więc `./gradlew`, dopóki wrapper nie zostanie odtworzony.

APK:
`usb_probe/app/build/outputs/apk/debug/app-debug.apk`.

## Procedura pomiarowa

1. Odłącz adapter.
2. Uruchom aplikację i naciśnij **Refresh USB**.
3. Podłącz adapter, wybierz urządzenie i udziel zgody.
4. Zapisz log bazowy: czy Android utworzył `eth0` / `usb0`.
5. Wybierz konkretny deskryptor. Dla WCH porównaj m.in. `id=1 alt=1 class=0x0A`, który ma bulk IN/OUT.
6. Naciśnij **Claim SELECTED interface (force=true)**. W razie potrzeby osobno użyj **Set alternate setting**.
7. Uruchom pojedynczy **Bulk IN** i zapisz wynik. Brak danych lub `-1` nie identyfikuje sam w sobie przyczyny.
8. Dopiero na odizolowanym segmencie użyj **Bulk OUT** i potwierdź transmisję ramki `0x88B5`.
9. Obserwuj równolegle `logcat`, a jeśli dostępny, `dmesg`; po testach naciśnij **Release / close**.

Zapisuj dokładny model adaptera, Android build, godzinę zdarzenia oraz logi aplikacji i systemu.

## Ważne ograniczenia

- Sukces `claimInterface()` nie dowodzi działania transmisji Ethernet ani trwałego odłączenia sterownika jądra.
- Sukces `bulkTransfer(OUT)` nie jest ACK z adaptera ani potwierdzeniem odbioru ramki po stronie sieci.
- Aplikacja nie implementuje na tym etapie handshake, CDC control requests ani profili per VID:PID. Nie wysyła losowych żądań sterujących.
- Enumeracja konfiguracji jest tylko odczytem. Aplikacja celowo nie wywołuje `setConfiguration()` i nie zakłada, że wie, która konfiguracja jest aktywna.
- `setInterface()` jest osobnym, ręcznym pomiarem. Nie wysyłamy losowych `controlTransfer()` i nie próbujemy sztucznie przejmować/zwalniać interfejsu kontrolnego jako rzekomego sygnału HANGUP.
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

**Nie wybieramy zwycięzcy na podstawie samego claim.** Następny pomiar to porównywalne wyniki bulk IN/OUT na konkretnych deskryptorach. Obsługa wielu modeli i ewentualne handshake powinny później powstać jako jawne profile protokołu, oparte na deskryptorach, dokumentacji i pomiarach, a nie na samym VID:PID.


## Kernel CDC recovery (WCH 1A86:5394)

The **Restore kernel cdc_ether (root)** action is a recovery/diagnostic path, not the normal bulk-transfer path.

1. Stop any active transfer. The action closes the app's `UsbDeviceConnection`.
2. Android displays the root-manager authorization prompt if `su` is available.
3. The command inspects `/sys/bus/usb/devices` for VID:PID `1a86:5394`, then checks the control interface `:2.0` and data interface `:2.1`.
4. If both interfaces already show `cdc_ether`, it reports that the driver is already bound. If the control interface is unbound, it tries to bind `cdc_ether`. If the control interface is already bound but the data interface is not, it tries a control-interface unbind/bind cycle to make the CDC driver probe again. It refuses to overwrite a different control-interface driver.
5. Check the log for `CONTROL_DRIVER_AFTER`, `DATA_DRIVER_AFTER`, and whether `eth0` reappears.

This operation requires a rooted Android build and a kernel exposing the relevant sysfs driver bind files. The USB Host API itself has no public method to bind a kernel driver. The action deliberately does not send undocumented vendor requests and does not touch the main Transparent Bridge app.

Build from the repository root:

```bash
./gradlew -p usb_probe assembleDebug --no-configuration-cache
```

Expected APK path:

`usb_probe/app/build/outputs/apk/debug/app-debug.apk`

A successful sysfs bind is not proof of working Ethernet traffic. Confirm that `cdc_ether` is bound, `eth0` exists, the link state is appropriate, and RX/TX counters change during a controlled network test.
