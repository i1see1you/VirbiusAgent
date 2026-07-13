PLUGIN_NAME = virbius-audit
PLUGIN_SO = libvirbius_audit.so

GO = go
LDFLAGS = -buildmode=c-shared -o $(PLUGIN_SO)

.PHONY: all clean test

all: $(PLUGIN_SO)

$(PLUGIN_SO): main.go event.go pidmap.go json_helper.go json_unmarshal.go consumer/redis.go
	$(GO) build $(LDFLAGS) .

test:
	$(GO) test ./...

clean:
	rm -f $(PLUGIN_SO) $(PLUGIN_NAME).h
