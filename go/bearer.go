// bearer.go
package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"os"
)

type BearerTokenGenerator struct {
	keyA []byte
	keyB []byte
}
var bearerGenerator = NewBearerTokenGenerator()

func NewBearerTokenGenerator() *BearerTokenGenerator {
	keyA := os.Getenv("KEY_A")
	keyB := os.Getenv("KEY_B")
	return &BearerTokenGenerator{
		keyA: []byte(keyA),
		keyB: []byte(keyB),
	}
}

func (btg *BearerTokenGenerator) GetBearerNew(bodyContent string, source string, formattedDate string, method string) (string, error) {
	combinedString := fmt.Sprintf("%s:%s:%s\n%s", method, source, formattedDate, bodyContent)
	combinedBytes := []byte(combinedString)
	mac := hmac.New(sha256.New, btg.keyB)
	mac.Write(combinedBytes)
	signature := mac.Sum(nil)
	encodedSignature := base64.StdEncoding.EncodeToString(signature)
	encodedKeyA := base64.StdEncoding.EncodeToString(btg.keyA)
	bearerToken := fmt.Sprintf("Bearer %s.%s", encodedKeyA, encodedSignature)
	return bearerToken, nil
}
