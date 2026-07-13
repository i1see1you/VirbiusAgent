module github.com/virbius/virbius-gateway/wasm

go 1.21

require (
	github.com/alibaba/higress/plugins/wasm-go v1.4.0
	github.com/tetratelabs/proxy-wasm-go-sdk v0.0.0-20240314015001-5906d39c1bce
	github.com/virbius/virbius-expr v0.0.0-00010101000000-000000000000
)

require (
	github.com/google/uuid v1.6.0 // indirect
	github.com/tidwall/gjson v1.17.0 // indirect
	github.com/tidwall/match v1.1.1 // indirect
	github.com/tidwall/pretty v1.1.0 // indirect
)

replace github.com/virbius/virbius-expr => ../../virbius-expr
