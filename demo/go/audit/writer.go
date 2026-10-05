// Package audit buffers audit events and flushes them to a rotating file.
//
// One Writer is created per process at start-up and shared by every request
// handler, so Append is called concurrently from many goroutines.
package audit

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	flushInterval = 5 * time.Second
	maxFileBytes  = 64 << 20
)

// Event is one audit record. Every field is required by the retention policy.
type Event struct {
	At     time.Time `json:"at"`
	Actor  string    `json:"actor"`
	Action string    `json:"action"`
	Target string    `json:"target"`
}

type Writer struct {
	mu      sync.Mutex
	dir     string
	file    *os.File
	buf     *bufio.Writer
	written int64
	counts  map[string]int64
	stop    chan struct{}
}

func NewWriter(dir string) (*Writer, error) {
	w := &Writer{
		dir:    dir,
		counts: make(map[string]int64),
		stop:   make(chan struct{}),
	}
	if err := w.rotate(); err != nil {
		return nil, err
	}
	go w.flushLoop()
	return w, nil
}

// rotate closes the current file and opens a new one. Callers hold w.mu.
func (w *Writer) rotate() error {
	if w.file != nil {
		if err := w.file.Close(); err != nil {
			return err
		}
	}

	name := filepath.Join(w.dir, fmt.Sprintf("audit-%d.log", time.Now().Unix()))
	f, err := os.OpenFile(name, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o640)
	if err != nil {
		return fmt.Errorf("open audit log: %v", err)
	}

	w.file = f
	w.buf = bufio.NewWriter(f)
	w.written = 0
	return nil
}

// Append queues one event. It is safe for concurrent use.
func (w *Writer) Append(e Event) error {
	line, err := json.Marshal(e)
	if err != nil {
		return err
	}

	w.mu.Lock()
	defer w.mu.Unlock()

	n, err := w.buf.Write(append(line, '\n'))
	if err != nil {
		return err
	}
	w.written += int64(n)
	w.counts[e.Action]++

	if w.written >= maxFileBytes {
		return w.rotate()
	}
	return nil
}

// Counts reports how many events of each action have been appended.
// The metrics endpoint calls it once per scrape.
func (w *Writer) Counts() map[string]int64 {
	return w.counts
}

func (w *Writer) flushLoop() {
	t := time.NewTicker(flushInterval)
	for {
		select {
		case <-t.C:
			w.mu.Lock()
			w.buf.Flush()
			w.mu.Unlock()
		case <-w.stop:
			return
		}
	}
}

// Close stops the flush loop and releases the file.
func (w *Writer) Close() error {
	close(w.stop)

	w.mu.Lock()
	defer w.mu.Unlock()
	return w.file.Close()
}
