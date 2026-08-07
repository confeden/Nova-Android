// Команда verify проверяет, что конфигурации, которые генерирует Nova,
// принимаются ядром с тем же набором зарегистрированных протоколов, что и в
// libnovaxray.so. Проверка «конфигурация валидна» и проверка «нужный транспорт
// зарегистрирован» — разные вещи: первую делает и полный дистрибутив Xray,
// вторую — только этот набор.
//
//	go run ./cmd/verify ../tools/probe/xray-configs/*.json
package main

import (
	"fmt"
	"os"
	"strings"

	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"

	_ "nova-xray/registry"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "укажите файлы конфигураций")
		os.Exit(2)
	}

	failed := 0
	for _, path := range os.Args[1:] {
		raw, err := os.ReadFile(path)
		if err != nil {
			fmt.Printf("%-28s ОШИБКА ЧТЕНИЯ: %v\n", path, err)
			failed++
			continue
		}
		config, err := serial.LoadJSONConfig(strings.NewReader(string(raw)))
		if err != nil {
			fmt.Printf("%-28s КОНФИГУРАЦИЯ: %v\n", path, err)
			failed++
			continue
		}
		instance, err := core.New(config)
		if err != nil {
			fmt.Printf("%-28s СБОРКА ЯДРА: %v\n", path, err)
			failed++
			continue
		}
		instance.Close()
		fmt.Printf("%-28s OK\n", path)
	}

	if failed > 0 {
		os.Exit(1)
	}
}
