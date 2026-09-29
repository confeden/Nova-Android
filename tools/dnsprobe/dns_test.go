package main

import (
	"context"
	"encoding/binary"
	"net"
	"strings"
	"testing"
	"time"
)

// TestRoundTrip: NewQuery + AddEDNS -> Encode -> ParseMessage preserves name, qtype, and OPT RR.
func TestRoundTrip(t *testing.T) {
	tests := []struct {
		name  string
		qtype uint16
	}{
		{"example.com", TypeA},
		{"test.long.domain.example.org", TypeTXT},
		{"a.b.c.d.e.f", TypeAAAA},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			q := NewQuery(tt.name, tt.qtype)
			q.AddEDNS(4096)

			wire, err := q.Encode()
			if err != nil {
				t.Fatalf("Encode: %v", err)
			}

			msg, err := ParseMessage(wire)
			if err != nil {
				t.Fatalf("ParseMessage: %v", err)
			}

			if len(msg.Questions) != 1 {
				t.Fatalf("expected 1 question, got %d", len(msg.Questions))
			}
			if msg.Questions[0].Name != tt.name {
				t.Errorf("name: got %q, want %q", msg.Questions[0].Name, tt.name)
			}
			if msg.Questions[0].Type != tt.qtype {
				t.Errorf("qtype: got %d, want %d", msg.Questions[0].Type, tt.qtype)
			}

			// Check OPT RR in additional
			foundOPT := false
			for _, rr := range msg.Additional {
				if rr.Type == TypeOPT {
					foundOPT = true
					if rr.UDPSize != 4096 {
						t.Errorf("OPT UDP size: got %d, want 4096", rr.UDPSize)
					}
				}
			}
			if !foundOPT {
				t.Error("OPT RR not found in additional section")
			}
		})
	}
}

// TestParseTXTRData: parse a hand-written response fixture with two TXT character-strings.
func TestParseTXTRData(t *testing.T) {
	// Build a minimal DNS response with one TXT RR containing two character-strings.
	// Header: 12 bytes
	var pkt []byte

	// Header
	hdr := DNSHeader{
		ID:      0x1234,
		Flags:   FlagQR | FlagRD | FlagRA,
		QDCount: 1,
		ANCount: 1,
		NSCount: 0,
		ARCount: 0,
	}
	pkt = append(pkt, hdr.encode()...)

	// Question: example.com A IN
	qname, _ := encodeName("example.com")
	pkt = append(pkt, qname...)
	pkt = binary.BigEndian.AppendUint16(pkt, TypeA)
	pkt = binary.BigEndian.AppendUint16(pkt, ClassIN)

	// Answer: example.com TXT IN TTL=300
	// RData: two character-strings: "hello" and "world"
	aname, _ := encodeName("example.com")
	pkt = append(pkt, aname...)
	pkt = binary.BigEndian.AppendUint16(pkt, TypeTXT)
	pkt = binary.BigEndian.AppendUint16(pkt, ClassIN)
	pkt = binary.BigEndian.AppendUint32(pkt, 300) // TTL

	// TXT RDATA: length-prefixed strings
	rdata := []byte{5, 'h', 'e', 'l', 'l', 'o', 5, 'w', 'o', 'r', 'l', 'd'}
	pkt = binary.BigEndian.AppendUint16(pkt, uint16(len(rdata)))
	pkt = append(pkt, rdata...)

	msg, err := ParseMessage(pkt)
	if err != nil {
		t.Fatalf("ParseMessage: %v", err)
	}

	if len(msg.Answers) != 1 {
		t.Fatalf("expected 1 answer, got %d", len(msg.Answers))
	}
	ans := msg.Answers[0]
	if ans.Type != TypeTXT {
		t.Fatalf("expected TXT type, got %d", ans.Type)
	}
	if len(ans.TXTData) != 2 {
		t.Fatalf("expected 2 TXT strings, got %d: %v", len(ans.TXTData), ans.TXTData)
	}
	if ans.TXTData[0] != "hello" {
		t.Errorf("TXT[0]: got %q, want %q", ans.TXTData[0], "hello")
	}
	if ans.TXTData[1] != "world" {
		t.Errorf("TXT[1]: got %q, want %q", ans.TXTData[1], "world")
	}
}

// TestParseCompressedName: parse a response with a compressed name pointer.
func TestParseCompressedName(t *testing.T) {
	// Build a packet where the answer name uses a compression pointer to the question name.
	var pkt []byte

	// Header
	hdr := DNSHeader{
		ID:      0xABCD,
		Flags:   FlagQR | FlagRD | FlagRA,
		QDCount: 1,
		ANCount: 1,
	}
	pkt = append(pkt, hdr.encode()...)

	// Question: example.com A IN — starts at offset 12
	qname, _ := encodeName("example.com")
	pkt = append(pkt, qname...)
	pkt = binary.BigEndian.AppendUint16(pkt, TypeA)
	pkt = binary.BigEndian.AppendUint16(pkt, ClassIN)

	// Answer: name via compression pointer to offset 12 (0xC00C)
	pkt = append(pkt, 0xC0, 0x0C) // pointer to offset 12
	pkt = binary.BigEndian.AppendUint16(pkt, TypeA)
	pkt = binary.BigEndian.AppendUint16(pkt, ClassIN)
	pkt = binary.BigEndian.AppendUint32(pkt, 60)  // TTL
	pkt = binary.BigEndian.AppendUint16(pkt, 4)   // RDLENGTH
	pkt = append(pkt, 93, 184, 216, 34)           // 93.184.216.34

	msg, err := ParseMessage(pkt)
	if err != nil {
		t.Fatalf("ParseMessage: %v", err)
	}

	if len(msg.Answers) != 1 {
		t.Fatalf("expected 1 answer, got %d", len(msg.Answers))
	}
	if msg.Answers[0].Name != "example.com" {
		t.Errorf("answer name: got %q, want %q", msg.Answers[0].Name, "example.com")
	}
	if msg.Answers[0].AAddr != "93.184.216.34" {
		t.Errorf("answer addr: got %q, want %q", msg.Answers[0].AAddr, "93.184.216.34")
	}
}

// TestBase32LabelSplit: for payloads 1..240 bytes, no label exceeds 63, total QNAME <= 255,
// and the result ends with the zone.
func TestBase32LabelSplit(t *testing.T) {
	zone := "t.example.com"
	for pl := 1; pl <= 240; pl++ {
		payload := make([]byte, pl)
		for i := range payload {
			payload[i] = byte(i % 256)
		}
		qname, err := Base32LabelSplit(payload, zone)
		if err != nil {
			// Some large payloads may exceed 255 bytes; that's expected.
			// Just verify it's a proper error, not a panic.
			if pl < 140 {
				t.Errorf("payload %d bytes: unexpected error: %v", pl, err)
			}
			continue
		}

		// Check it ends with the zone
		if !strings.HasSuffix(qname, zone) {
			t.Errorf("payload %d: qname %q does not end with zone %q", pl, qname, zone)
		}

		// Check no label exceeds 63 bytes
		labels := strings.Split(qname, ".")
		for _, l := range labels {
			if len(l) > 63 {
				t.Errorf("payload %d: label %q exceeds 63 bytes (%d)", pl, l, len(l))
			}
		}

		// Check total encoded QNAME length
		encoded, err := encodeName(qname)
		if err != nil {
			t.Errorf("payload %d: encodeName(%q): %v", pl, qname, err)
			continue
		}
		if len(encoded) > 255 {
			t.Errorf("payload %d: encoded QNAME length %d exceeds 255", pl, len(encoded))
		}
	}
}

// TestRandomLabel: returns n DNS-legal characters and differs across calls.
func TestRandomLabel(t *testing.T) {
	label := RandomLabel(12)
	if len(label) != 12 {
		t.Errorf("RandomLabel(12) length: got %d, want 12", len(label))
	}
	for _, c := range label {
		if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
			t.Errorf("RandomLabel(12) contains non-DNS character %q", c)
		}
	}

	// Should differ across calls (with overwhelming probability)
	label2 := RandomLabel(12)
	if label == label2 {
		t.Errorf("RandomLabel(12) returned identical values: %q", label)
	}
}

// TestEndpointParser: ProbeDoHReach dispatches the four endpoint forms correctly.
func TestEndpointParser(t *testing.T) {
	// Use a very short timeout so network failures are instant.
	// We don't care about success; we only verify protocol and fail_stage.
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	endpoints := []string{
		"https://cloudflare-dns.com/dns-query",
		"dot://1.1.1.1",
		"dot://1.1.1.1:8853",
		"1.1.1.1",
		"1.1.1.1:8853",
		"ftp://nope.example",
	}

	// Pass a resolver that will fail fast for hostnames (we don't need real resolution).
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			// Use an unreachable address so hostname resolution fails quickly.
			d := net.Dialer{Timeout: 10 * time.Millisecond}
			return d.DialContext(ctx, "udp", "127.0.0.1:1")
		},
	}

	result := ProbeDoHReach(ctx, endpoints, 50*time.Millisecond, true, resolver, nil, true)

	if len(result.Results) != len(endpoints) {
		t.Fatalf("expected %d results, got %d", len(endpoints), len(result.Results))
	}

	// 0: https:// -> doh
	if result.Results[0].Protocol != "doh" {
		t.Errorf("[0] expected protocol doh, got %q", result.Results[0].Protocol)
	}
	if result.Results[0].URL != "https://cloudflare-dns.com/dns-query" {
		t.Errorf("[0] expected URL preserved, got %q", result.Results[0].URL)
	}

	// 1: dot://1.1.1.1 -> dot
	if result.Results[1].Protocol != "dot" {
		t.Errorf("[1] expected protocol dot, got %q", result.Results[1].Protocol)
	}
	if result.Results[1].URL != "dot://1.1.1.1" {
		t.Errorf("[1] expected URL dot://1.1.1.1, got %q", result.Results[1].URL)
	}

	// 2: dot://1.1.1.1:8853 -> dot
	if result.Results[2].Protocol != "dot" {
		t.Errorf("[2] expected protocol dot, got %q", result.Results[2].Protocol)
	}
	if result.Results[2].URL != "dot://1.1.1.1:8853" {
		t.Errorf("[2] expected URL dot://1.1.1.1:8853, got %q", result.Results[2].URL)
	}

	// 3: bare host -> dot
	if result.Results[3].Protocol != "dot" {
		t.Errorf("[3] expected protocol dot, got %q", result.Results[3].Protocol)
	}

	// 4: bare host:port -> dot
	if result.Results[4].Protocol != "dot" {
		t.Errorf("[4] expected protocol dot, got %q", result.Results[4].Protocol)
	}

	// 5: ftp:// -> bad_endpoint
	if result.Results[5].FailStage != "bad_endpoint" {
		t.Errorf("[5] expected fail_stage bad_endpoint, got %q", result.Results[5].FailStage)
	}
	if result.Results[5].Protocol != "unknown" {
		t.Errorf("[5] expected protocol unknown, got %q", result.Results[5].Protocol)
	}
}
