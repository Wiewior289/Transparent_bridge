# TRUE_BRIDGE: architektura i kolejność implementacji

Status: plan wykonawczy dla gałęzi true_bridge. Żaden etap nie jest produkcyjnie zweryfikowany bez testów jednostkowych, CI i odpowiedniego testu laboratoryjnego.

## Cel i ograniczenia

Transparentny bridge L3 pomiędzy PLC/HMI podłączonym przez USB Ethernet a tailnetem Tailscale. APK działa na stock Androidzie, bez roota, bez zakładania istnienia eth0, iptables, systemowego TUN ani możliwości modyfikowania jądra.

USB transportuje kompletne ramki Ethernet. Bridge przetwarza IPv4, realizuje kontrolowany SNAT/DNAT i wymianę pakietów z osadzonym silnikiem Tailscale. To nie jest tunel L2: Tailscale przenosi pakiety IP, nie oryginalne ramki Ethernet.

## Architektura docelowa

~~~text
TAILNET
  |
Tailscale userspace wgengine / WireGuard
  |
UserlandTun (custom tun.Device, Go)
  | JNI: raw IPv4 packets
  v
IPv4 validation + flow conntrack + SNAT
  |
ARP cache / next-hop resolution
  |
Ethernet frame encoder
  |
UsbEcmTransport (Android USB Host)
  |
CDC-ECM Bulk OUT -> adapter -> PLC

PLC -> adapter -> CDC-ECM Bulk IN
  |
UsbEcmTransport
  |
Ethernet / ARP / IPv4 parser
  |
reverse conntrack + DNAT
  | JNI: raw IPv4 packets
  v
UserlandTun -> Tailscale -> tailnet
~~~

## Niezmienniki

1. Stock ROM / zero root. Nie wywołujemy iptables, ip, ifconfig; nie wymagamy eth0 ani /dev/net/tun.
2. Jedna implementacja CDC-ECM współdzielona przez app i usb_probe.
3. USB zna endpointy i transfery; transport zwraca kompletne ramki Ethernet; NAT zna pakiety IP/TCP/UDP/ICMP; Tailscale dostaje pakiety IP.
4. Union descriptor definiuje relację control/data; bez heurystyki sąsiedztwa numerów interfejsów.
5. Brak FTP/FTPS ALG. Nie analizujemy payloadów FTP, nie otwieramy portów na podstawie 227/229 i nie obchodzimy TLS. Każde połączenie PASV/EPSV jest osobnym przepływem TCP i otrzymuje własną sesję conntrack.
6. NAT używa pełnej krotki przepływu. Reverse lookup weryfikuje zdalny IP/port i protokół.
7. Fragmenty IPv4 i nieobsługiwane protokoły są jawnie odrzucane, nigdy przepuszczane bez NAT.
8. Bez fałszywego zero-copy. Kopiowanie buforów jest raportowane jako kopiowanie.
9. Transfer synchroniczny jest bazą. bulkTransfer nie jest opisywany jako wielo-URB; UsbRequest to osobny backend.
10. MATCHED, DESCRIPTORS_VALID, BOUND, TRANSPORT_READY, RX_TX_VERIFIED i TAILNET_READY są odrębnymi stanami.

## Etapy prac i bramki odbiorcze

### Etap 0 — punkt odniesienia
- Zachować sprzętowo potwierdzony commit 558d331 jako bazę.
- Rozdzielić testy czystej logiki i testy laboratoryjne.
- Usunąć twierdzenia o funkcjach, których kod nie wykonuje.
Gate: odtwarzalny build probe i apk.

### Etap 1 — wspólny moduł CDC-ECM
- Android Library :usb_ecm.
- Wspólny CdcDescriptorScanner, CdcEcmProfile, CdcEcmBinder.
- UsbEcmTransport udostępnia wyłącznie complete-frame send/receive, MAC, parametry endpointów i liczniki.
- Produkcyjny UsbEthernetManager używa tego transportu dla CDC-ECM.
- Legacy Realtek path pozostaje oddzielnym backendem.
Gate: CI + na telefonie identyczny CDC bind, ARP i ICMP przez probe i produkcyjny manager.

### Etap 2 — transport i lifecycle
- Jeden właściciel połączenia USB.
- Liczniki RX/TX frames, bytes, błędów, timeoutów (o ile API pozwala je rozdzielić), malformed frames, drop i disconnect.
- Testy reconnect, permission revoke, close/release.
- Nie stosować ZLP/pad heurystyki bez pomiaru dla danego adaptera.
Gate: soak RX/TX i wielokrotne unplug/replug bez wiszących workerów.

### Etap 3 — IPv4 i conntrack bez ALG
- Walidacja Version, IHL, Total Length, header checksum i granic nagłówków.
- Odrzucanie fragmentacji do czasu obsługi.
- TCP, UDP i ICMP Echo; jawny DROP dla pozostałych.
- Pełna krotka (protocol, source IP/port, destination IP/port) i dokładny reverse tuple.
- ICMP identifier mapping oraz checksum update.
- Timeouty sesji.
- Usunięcie ALG i powiązanych pól/UI. PASV/EPSV jako zwykły kolejny flow.
Gate: testy kolizji portów, wielu klientów/PLC, obcego remote IP/port, ICMP, UDP checksum zero/non-zero, malformed i fragmentów.

### Etap 4 — MTU/MSS i Ethernet/ARP
- Zgodność UserlandTun.MTU, EthernetConfig MTU i MSS.
- MSS dla IPv4/TCP nie większy niż MTU minus nagłówki IP/TCP; MTU 1360 daje punkt wyjścia 1320.
- Minimalna długość ramki Ethernet dla ARP, walidacja ARP sender MAC/IP.
- TTL decrement i checksum update na forward path.
Gate: capture po obu stronach, duże transfery TCP, brak niespodziewanej fragmentacji.

### Etap 5 — asynchroniczny USB backend
- Zachować API transportu ramek.
- Opcjonalny UsbRequest dla API 26+, fallback synchroniczny API 24–25.
- ByteBuffer wraca do puli dopiero po completion.
- Testy anulowania, timeoutów, lifecycle i wyścigów.
Gate: pomiary CPU/throughput/latency względem synchronicznego baseline.

### Etap 6 — Tailscale readiness i pełna ścieżka
- Weryfikować rzeczywisty stan backendu, nie sam JNI return code.
- Potwierdzić auth, Tailscale IP, reklamę trasy, jej approval w admin console i ACL.
- Zdalny host tailnet -> PLC: ICMP, TCP, UDP; odpowiedzi PLC i wiele równoległych flow.
- FTPS/TLS/PASV bez ALG.
Gate: udokumentowany end-to-end flow z 5-tuple, mapowaniem NAT i walidacją checksum.

### Etap 7 — stabilność i wydajność
- Soak, reconnect, sleep/wake, thermal throttling, queue pressure.
- Jawne limity kolejek i polityka drop.
- Profilowanie alokacji/GC/kopiowania przed optymalizacją.
Gate: raport throughput, RTT, loss, CPU, temperatury.

## Aktualny stan

| Obszar | Stan |
|---|---|
| CDC discovery/bind w probe | sprzętowo potwierdzony |
| ARP + IPv4 ICMP przez probe | sprzętowo potwierdzony |
| wspólny transport używany przez app | etap 1 w implementacji |
| produkcyjny packet pipeline | szkic, wymaga walidacji |
| full-tuple NAT / ICMP reverse | do implementacji |
| ALG | do usunięcia |
| UsbRequest | późniejszy etap |
| Tailscale end-to-end z PLC | niepotwierdzone sprzętowo |

## Warunek ukończenia

TRUE_BRIDGE nie oznacza tylko build-success, udanego CDC bind ani startu Go backendu. Wymaga poprawnego USB RX/TX, poprawnego NAT i rzeczywistego pakietu z tailnetu do PLC oraz odpowiedzi wracającej tym samym flow.
