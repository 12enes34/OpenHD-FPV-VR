# FPV VR – OpenHD görüntüsü, telefonla VR gözlükte

[English → README.md](README.md) · **[APK'yı indir](../../releases/latest)**

![FPV VR, VR modunda uçuş kontrolcüsünün menüsüyle (test görüntüsü)](docs/vr_osd_menu.png)

Bu Android uygulaması **OpenHD** görüntüsünü ve uçuş kontrolcüsünün **OSD**'sini, VR gözlüğe
(Samsung Gear VR, Google Cardboard ya da iki mercekli herhangi bir "VR kutusu") takılan telefonda
gösterir. Kullanımı DJI gözlüğe benzer.

[fc-osd köprüsüyle](https://github.com/12enes34/OpenHD-Pavo_20_pocket_OSD/tree/fc-osd-bridge/tools/fc-osd/README_TR.md) birlikte kullanınca FC'nin menüleri, uyarıları, voltajı ve uçuş modu
görüntünün içinde görünür. Köprü olmadan, düz OpenHD ile de çalışır.

* İki göz için yan yana görüntü; mercek bükmesi ve renk saçağı düzeltmesi.
* Düşük gecikmeli donanım H.264 çözme: Galaxy S10'da yaklaşık 5–6 ms.
* OSD'yi telefon çizer. Görüntü yeniden sıkıştırılmaz, bu yüzden ek gecikme olmaz.
* Hizalama ızgarası ve ayarlar: göz arası, kaydırma, görüntü boyutu, bükme, OSD alanı. Telefonun
  gözlüğe takıldığı iki yön için ayrı ayarlar saklanır.
* İnternet, hesap ya da ek kütüphane gerekmez. APK yaklaşık 35 KB.

## Kurulum

1. Releases sayfasından `fpv-vr-<sürüm>.apk` dosyasını indirip telefonda aç. Android bu kaynaktan
   yüklemeye izin vermeni ister.
2. Telefonu OpenHD yer istasyonuna USB kabloyla bağla.
3. **FPV VR**'ı aç, ekrana dokun ve **USB paylaşımı** düğmesine bas. **USB bağlantı paylaşımını** aç.
   Bunu kabloyu her taktığında yapman gerekir; Android, kablo çekilince paylaşımı kapatır.
4. Uygulamaya dön. Birkaç saniye içinde drone'un görüntüsü ve OSD'si gelir.

SIM kart gerekmez. OpenHD'de hiçbir ayar yapmana gerek yoktur: yer istasyonu USB paylaşımıyla bağlanan
telefonu kendisi bulur. Görüntüyü UDP 5600, telemetriyi UDP 14550 üzerinden gönderir.

## Gözlüğe uydurma (bir kez)

1. Ekrana dokun ve hazır ayarlardan birini seç: **Samsung Gear VR** ya da **Cardboard / VR kutusu**.
2. **Hizalama ızgarasını** aç. Sol gözde kırmızı, sağ gözde mavi nokta görünür.
3. Telefonu gözlüğe tak ve iki gözünle bak:
   * **İki nokta görüyorsan:** **Göz arası** ayarını değiştir. Kırmızı mavinin solundaysa görüntüler fazla
     uzaktır, küçült. Sağındaysa fazla yakındır, büyüt.
   * **Görüntü ortalı değilse ya da bir kenar kesiliyorsa:** **Yatay / Dikey kaydırma** ayarını kullan.
   * **Köşeler bulanık ya da yarım görünüyorsa:** **Görüntü boyutunu** küçült ya da **OSD alanını**
     küçülterek yazıları ortaya topla.
4. Izgarayı kapat. Ayarlar kaydedilir.

Göz arası ve yatay kaydırma, telefonun takıldığı iki yön için (şarj soketi solda ya da sağda) ayrı
saklanır. Netlik için gözlüğün odak tekerleğini kullan.

## Sorun giderme

| Ekranda | Anlamı / çözüm |
|---|---|
| *USB paylaşımı KAPALI* | Ekrana dokun, **USB paylaşımı** düğmesine bas ve paylaşımı aç. |
| *USB paylaşımı açık, OpenHD yer istasyonu bekleniyor* | Yer istasyonunda OpenHD'yi başlat. Böyle kalırsa kabloyu çıkarıp tak ve paylaşımı yeniden aç. |
| *TELEMETRİ YOK* | Görüntü geliyor ama MAVLink gelmiyor. Hava birimindeki FC bağlantısını kontrol et. |
| Görüntü takılıyor | **İstatistik**'i aç. *lost* değeri, paketi eksik gelen kareleri sayar. Sebep telefon değil, telsiz bağlantısıdır. |

Kendin derlemek için [README.md](README.md) dosyasındaki "Build it yourself" bölümüne bak.

Lisans: GPL-3.0 (OpenHD gibi).
