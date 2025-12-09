# Makefile for building JNI library for SPICE client

JAVA_HOME ?= /usr/lib/jvm/default-java
SPICE_CFLAGS = $(shell pkg-config --cflags spice-client-glib-2.0)
SPICE_LIBS = $(shell pkg-config --libs spice-client-glib-2.0)

SRC_DIR = src/main/c
OBJ_DIR = build/obj
LIB_DIR = build/libs

SOURCES = $(SRC_DIR)/com_mcdigital_ecosystem_spice_SpiceClientJNI.c
OBJECTS = $(OBJ_DIR)/com_mcdigital_ecosystem_spice_SpiceClientJNI.o

# Determine OS and architecture
UNAME_S := $(shell uname -s)
UNAME_M := $(shell uname -m)

ifeq ($(UNAME_S),Linux)
    LIB_EXT = so
    LIB_PREFIX = lib
    ifeq ($(UNAME_M),x86_64)
        ARCH = linux-x86_64
    else ifeq ($(UNAME_M),aarch64)
        ARCH = linux-aarch64
    endif
endif

ifeq ($(UNAME_S),Darwin)
    LIB_EXT = dylib
    LIB_PREFIX = lib
    ifeq ($(UNAME_M),x86_64)
        ARCH = darwin-x86_64
    else ifeq ($(UNAME_M),arm64)
        ARCH = darwin-aarch64
    endif
endif

ifeq ($(UNAME_S),MINGW32_NT-6.1)
    LIB_EXT = dll
    LIB_PREFIX = 
    ARCH = win32-x86_64
endif

TARGET = $(LIB_DIR)/$(LIB_PREFIX)spiceclient.$(LIB_EXT)

CFLAGS = -fPIC -shared -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/$(shell uname -s | tr '[:upper:]' '[:lower:]') $(SPICE_CFLAGS) -O2 -Wall
LDFLAGS = $(SPICE_LIBS) -shared

.PHONY: all clean

all: $(TARGET)

$(TARGET): $(OBJECTS) | $(LIB_DIR)
	$(CC) $(LDFLAGS) -o $@ $^

$(OBJ_DIR)/%.o: $(SRC_DIR)/%.c | $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(OBJ_DIR):
	mkdir -p $(OBJ_DIR)

$(LIB_DIR):
	mkdir -p $(LIB_DIR)

clean:
	rm -rf $(OBJ_DIR) $(LIB_DIR)

