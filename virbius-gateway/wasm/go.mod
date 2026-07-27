module github.com/virbius/virbius-gateway/wasm

go 1.21

require (
	github.com/alibaba/higress/plugins/wasm-go v1.4.0
	github.com/higress-group/proxy-wasm-go-sdk v1.0.1
	github.com/tidwall/gjson v1.17.0
	github.com/tidwall/resp v0.1.1
	github.com/virbius/virbius-expr v0.0.0-00010101000000-000000000000
)

require (
	github.com/google/uuid v1.6.0 // indirect
	github.com/higress-group/nottinygc v0.0.0-20231101025119-e93c4c2f8520 // indirect
	github.com/magefile/mage v1.14.0 // indirect
	github.com/stretchr/testify v1.9.0 // indirect
	github.com/tidwall/match v1.1.1 // indirect
	github.com/tidwall/pretty v1.2.0 // indirect
)

replace github.com/virbius/virbius-expr => ../../virbius-expr
