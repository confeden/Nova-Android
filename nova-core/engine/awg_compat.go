package nova

import (
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
	"sync/atomic"
)

type awgCompatConfig struct {
	junkCount int
	junkMin   int
	junkMax   int
	ipackets  [5]*awgCompatChain
	active    bool
}

type awgCompatChain struct {
	spec   string
	tokens []awgCompatToken
}

type awgCompatToken interface {
	appendTo(dst []byte) ([]byte, error)
}

type awgCompatBytesToken struct {
	data []byte
}

type awgCompatRandomToken struct {
	length int
}

type awgCompatRandomDigitsToken struct {
	length int
}

type awgCompatRandomCharsToken struct {
	length int
}

type awgCompatTimestampToken struct{}

var awgCompatExplicitActive atomic.Bool

func ResetAwgCompatConfig() {
	awgCompatExplicitActive.Store(false)
}

func MarkAwgCompatActive() {
	awgCompatExplicitActive.Store(true)
}

func SetAwgCompatJunkCount(count int) error {
	if count < 0 {
		return fmt.Errorf("AWG Jc must be non-negative")
	}
	return nil
}

func SetAwgCompatJunkMin(size int) error {
	if size < 0 {
		return fmt.Errorf("AWG Jmin must be non-negative")
	}
	return nil
}

func SetAwgCompatJunkMax(size int) error {
	if size < 0 {
		return fmt.Errorf("AWG Jmax must be non-negative")
	}
	return nil
}

func SetAwgCompatIPacketSpec(index int, spec string) error {
	if index < 0 || index >= 5 {
		return fmt.Errorf("AWG I%d index out of range", index+1)
	}
	if strings.TrimSpace(spec) == "" {
		return nil
	}
	if _, err := parseAwgCompatChain(strings.TrimSpace(spec)); err != nil {
		return fmt.Errorf("failed to parse AWG I%d: %w", index+1, err)
	}
	return nil
}

func ValidateAwgCompatPadding(key string, value string) error {
	padding, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil {
		return fmt.Errorf("failed to parse %s: %w", strings.ToUpper(key), err)
	}
	if padding < 0 {
		return fmt.Errorf("%s must be non-negative", strings.ToUpper(key))
	}
	return nil
}

func ValidateAwgCompatHeader(key string, value string) error {
	spec := strings.TrimSpace(value)
	if spec == "" {
		return nil
	}
	switch strings.ToLower(strings.TrimSpace(key)) {
	case "h1", "h2", "h3", "h4":
	default:
		return fmt.Errorf("unknown AWG header key: %s", key)
	}
	if _, _, err := parseAwgCompatHeaderSpec(spec); err != nil {
		return fmt.Errorf("failed to parse %s: %w", strings.ToUpper(key), err)
	}
	return nil
}

func FinalizeAwgCompatConfig() error {
	return nil
}

func hasExplicitAwgCompatConfig() bool {
	return awgCompatExplicitActive.Load()
}

func (b *SmartBind) injectExplicitAwgCompatHandshake(novaEp *NovaEndpoint) {
}

func parseAwgCompatHeaderSpec(spec string) (int, int, error) {
	trimmed := strings.TrimSpace(spec)
	if trimmed == "" {
		return 0, 0, fmt.Errorf("empty header spec")
	}
	if strings.Contains(trimmed, "-") {
		parts := strings.SplitN(trimmed, "-", 2)
		if len(parts) != 2 {
			return 0, 0, fmt.Errorf("invalid range")
		}
		start, err := strconv.Atoi(strings.TrimSpace(parts[0]))
		if err != nil {
			return 0, 0, err
		}
		end, err := strconv.Atoi(strings.TrimSpace(parts[1]))
		if err != nil {
			return 0, 0, err
		}
		if start < 0 || end < 0 || end < start {
			return 0, 0, fmt.Errorf("invalid header range")
		}
		return start, end, nil
	}
	value, err := strconv.Atoi(trimmed)
	if err != nil {
		return 0, 0, err
	}
	if value < 0 {
		return 0, 0, fmt.Errorf("header value must be non-negative")
	}
	return value, value, nil
}

func parseAwgCompatChain(spec string) (*awgCompatChain, error) {
	remaining := spec
	tokens := make([]awgCompatToken, 0, 4)

	for {
		start := strings.IndexByte(remaining, '<')
		if start == -1 {
			break
		}
		endOffset := strings.IndexByte(remaining[start:], '>')
		if endOffset == -1 {
			return nil, fmt.Errorf("missing closing >")
		}
		end := start + endOffset
		tag := strings.TrimSpace(remaining[start+1 : end])
		remaining = remaining[end+1:]

		if tag == "" {
			return nil, fmt.Errorf("empty AWG tag")
		}
		parts := strings.Fields(tag)
		key := strings.ToLower(parts[0])
		value := ""
		if len(parts) > 1 {
			value = parts[1]
		}

		token, err := buildAwgCompatToken(key, value)
		if err != nil {
			return nil, err
		}
		tokens = append(tokens, token)
	}

	if len(tokens) == 0 {
		return nil, fmt.Errorf("AWG packet spec contains no supported tags")
	}

	return &awgCompatChain{
		spec:   spec,
		tokens: tokens,
	}, nil
}

func buildAwgCompatToken(key string, value string) (awgCompatToken, error) {
	switch key {
	case "b":
		raw := strings.TrimSpace(strings.TrimPrefix(value, "0x"))
		if raw == "" {
			return nil, fmt.Errorf("AWG <b> requires hex data")
		}
		if len(raw)%2 != 0 {
			return nil, fmt.Errorf("AWG <b> requires an even-length hex payload")
		}
		decoded, err := hex.DecodeString(raw)
		if err != nil {
			return nil, err
		}
		return awgCompatBytesToken{data: decoded}, nil
	case "r":
		length, err := parseAwgCompatTokenLength(key, value)
		if err != nil {
			return nil, err
		}
		return awgCompatRandomToken{length: length}, nil
	case "rd":
		length, err := parseAwgCompatTokenLength(key, value)
		if err != nil {
			return nil, err
		}
		return awgCompatRandomDigitsToken{length: length}, nil
	case "rc":
		length, err := parseAwgCompatTokenLength(key, value)
		if err != nil {
			return nil, err
		}
		return awgCompatRandomCharsToken{length: length}, nil
	case "t":
		return awgCompatTimestampToken{}, nil
	default:
		return nil, fmt.Errorf("unsupported AWG tag <%s>", key)
	}
}

func parseAwgCompatTokenLength(key string, value string) (int, error) {
	length, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil {
		return 0, fmt.Errorf("AWG <%s> requires an integer length: %w", key, err)
	}
	if length < 0 {
		return 0, fmt.Errorf("AWG <%s> length must be non-negative", key)
	}
	return length, nil
}

func (c *awgCompatChain) Build() ([]byte, error) {
	buf := make([]byte, 0, 64)
	var err error
	for _, token := range c.tokens {
		buf, err = token.appendTo(buf)
		if err != nil {
			return nil, err
		}
	}
	return buf, nil
}

func (t awgCompatBytesToken) appendTo(dst []byte) ([]byte, error) {
	return append(dst, t.data...), nil
}

func (t awgCompatRandomToken) appendTo(dst []byte) ([]byte, error) {
	return append(dst, make([]byte, t.length)...), nil
}

func (t awgCompatRandomDigitsToken) appendTo(dst []byte) ([]byte, error) {
	return append(dst, make([]byte, t.length)...), nil
}

func (t awgCompatRandomCharsToken) appendTo(dst []byte) ([]byte, error) {
	return append(dst, make([]byte, t.length)...), nil
}

func (t awgCompatTimestampToken) appendTo(dst []byte) ([]byte, error) {
	return append(dst, 0, 0, 0, 0), nil
}
