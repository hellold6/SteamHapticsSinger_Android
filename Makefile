PKG_CONFIG ?= pkg-config
CXX ?= c++
CC ?= cc

HIDAPI_PKG := $(shell for p in hidapi-hidraw hidapi-libusb hidapi; do $(PKG_CONFIG) --exists $$p && { echo $$p; break; }; done)

ifeq ($(strip $(HIDAPI_PKG)),)
$(error Could not find a hidapi pkg-config package. Tried: hidapi-hidraw, hidapi-libusb, hidapi)
endif

PKG_LIBS := libusb-1.0 $(HIDAPI_PKG)
CFLAGS += $(shell $(PKG_CONFIG) --cflags $(PKG_LIBS))
LDFLAGS += $(shell $(PKG_CONFIG) --libs $(PKG_LIBS))

all: steam-haptics-singer

steam-haptics-singer: main.o midifile/midifile.o
	$(CXX) -o $@ $^ $(LDFLAGS)

main.o: main.cpp
	$(CXX) $(CFLAGS) -c -o $@ $<

midifile/midifile.o: midifile/midifile.c
	$(CC) $(CFLAGS) -c -o $@ $<

clean:
	rm -f steam-haptics-singer main.o midifile/midifile.o
