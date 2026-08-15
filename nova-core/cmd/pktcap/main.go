// Command pktcap — минимальный захват пакетов для телефона с root.
//
// Зачем свой, а не tcpdump: на тестовых устройствах его нет, а тянуть посторонний
// двоичный файл ради одного замера не хочется. Нужен ровно один ответ: доходит ли до
// сокета ответ сервера на запрос CONNECT-IP, или сервер действительно молчит. Для этого
// достаточно сырого AF_PACKET, фильтра по адресу и вывода в pcap — дальше файл читается
// чем угодно.
//
// Запуск на устройстве (нужен root):
//
//	su -c '/data/local/tmp/pktcap -i wlan0 -host 162.159.198.1 -w /data/local/tmp/cap.pcap -d 90s'
package main

import (
	"encoding/binary"
	"flag"
	"log"
	"net"
	"os"
	"syscall"
	"time"
)

const (
	ethPAll     = 0x0003
	ethHdrLen   = 14
	ethTypeIPv4 = 0x0800
	pcapMagic   = 0xa1b2c3d4
	linkEther   = 1
	snapLen     = 2048
)

func htons(v uint16) uint16 { return v<<8 | v>>8 }

func main() {
	ifaceFlag := flag.String("i", "wlan0", "интерфейс")
	outFlag := flag.String("w", "/data/local/tmp/cap.pcap", "файл pcap")
	hostFlag := flag.String("host", "", "оставлять только пакеты с этим адресом IPv4 (обязательно)")
	durFlag := flag.Duration("d", 90*time.Second, "сколько ловить")
	flag.Parse()

	if *hostFlag == "" {
		log.Fatalf("нужен -host: без фильтра файл вырастет на весь трафик телефона")
	}
	target := net.ParseIP(*hostFlag).To4()
	if target == nil {
		log.Fatalf("-host должен быть адресом IPv4, получено %q", *hostFlag)
	}

	ifi, err := net.InterfaceByName(*ifaceFlag)
	if err != nil {
		log.Fatalf("интерфейс %s: %v", *ifaceFlag, err)
	}

	fd, err := syscall.Socket(syscall.AF_PACKET, syscall.SOCK_RAW, int(htons(ethPAll)))
	if err != nil {
		log.Fatalf("сырой сокет (нужен root): %v", err)
	}
	defer syscall.Close(fd)

	if err := syscall.Bind(fd, &syscall.SockaddrLinklayer{
		Protocol: htons(ethPAll),
		Ifindex:  ifi.Index,
	}); err != nil {
		log.Fatalf("bind на %s: %v", *ifaceFlag, err)
	}

	file, err := os.Create(*outFlag)
	if err != nil {
		log.Fatalf("создать %s: %v", *outFlag, err)
	}
	defer file.Close()
	if err := writePcapHeader(file); err != nil {
		log.Fatalf("заголовок pcap: %v", err)
	}

	// Срок на чтении, а не только общий: иначе последний Recvfrom заблокировал бы
	// выход до первого же пакета после окончания замера.
	tv := syscall.NsecToTimeval(int64(500 * time.Millisecond))
	_ = syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &tv)

	log.Printf("ловим %s на %s, фильтр %s", *durFlag, *ifaceFlag, target)
	deadline := time.Now().Add(*durFlag)
	buf := make([]byte, snapLen)
	kept, seen := 0, 0

	for time.Now().Before(deadline) {
		n, _, err := syscall.Recvfrom(fd, buf, 0)
		if err != nil {
			if err == syscall.EAGAIN || err == syscall.EINTR {
				continue
			}
			log.Printf("чтение прервано: %v", err)
			break
		}
		if n < ethHdrLen+20 {
			continue
		}
		seen++
		if binary.BigEndian.Uint16(buf[12:14]) != ethTypeIPv4 {
			continue
		}
		ip := buf[ethHdrLen:]
		src, dst := ip[12:16], ip[16:20]
		if !equalIPv4(src, target) && !equalIPv4(dst, target) {
			continue
		}
		if err := writePcapRecord(file, buf[:n], time.Now()); err != nil {
			log.Fatalf("запись пакета: %v", err)
		}
		kept++
	}

	log.Printf("готово: сохранено %d пакетов из %d просмотренных, файл %s", kept, seen, *outFlag)
}

func equalIPv4(a []byte, b net.IP) bool {
	return len(a) == 4 && a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3]
}

func writePcapHeader(f *os.File) error {
	head := make([]byte, 24)
	binary.LittleEndian.PutUint32(head[0:], pcapMagic)
	binary.LittleEndian.PutUint16(head[4:], 2)
	binary.LittleEndian.PutUint16(head[6:], 4)
	binary.LittleEndian.PutUint32(head[16:], snapLen)
	binary.LittleEndian.PutUint32(head[20:], linkEther)
	_, err := f.Write(head)
	return err
}

func writePcapRecord(f *os.File, payload []byte, ts time.Time) error {
	rec := make([]byte, 16)
	binary.LittleEndian.PutUint32(rec[0:], uint32(ts.Unix()))
	binary.LittleEndian.PutUint32(rec[4:], uint32(ts.Nanosecond()/1000))
	binary.LittleEndian.PutUint32(rec[8:], uint32(len(payload)))
	binary.LittleEndian.PutUint32(rec[12:], uint32(len(payload)))
	if _, err := f.Write(rec); err != nil {
		return err
	}
	_, err := f.Write(payload)
	return err
}

