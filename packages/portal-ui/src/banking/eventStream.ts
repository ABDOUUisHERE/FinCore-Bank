export async function readEventStream(reader: ReadableStreamDefaultReader<Uint8Array>, changed: () => void) {
  const decoder = new TextDecoder(); let buffer = "";
  try {
    for (;;) {
      const next = await reader.read();
      if (next.done) break;
      buffer += decoder.decode(next.value, { stream: true });
      if (buffer.length > 65536) throw new Error("Invalid notification stream");
      for (;;) {
        const boundary = /\r?\n\r?\n/.exec(buffer);
        if (!boundary || boundary.index === undefined) break;
        const frame = buffer.slice(0, boundary.index); buffer = buffer.slice(boundary.index + boundary[0].length);
        if (frame.split(/\r?\n/).some(line => line.trim() === "event:notification.changed" || line.trim() === "event: notification.changed")) changed();
      }
    }
  } finally { await reader.cancel().catch(() => undefined); reader.releaseLock(); }
}
