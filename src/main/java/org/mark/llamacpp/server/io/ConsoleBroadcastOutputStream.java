package org.mark.llamacpp.server.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.mark.llamacpp.server.LlamaServer;

public class ConsoleBroadcastOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final Charset charset;
    private final StringBuilder buffer = new StringBuilder();
    private volatile boolean closed = false;
    
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm.ss.SSS");

    public ConsoleBroadcastOutputStream(OutputStream delegate, Charset charset) {
        this.delegate = delegate;
        this.charset = charset;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        delegate.write(b);
        char c = (char) (b & 0xFF);
        if (c == '\n') {
            String line = this.sdf.format(new Date()) + "-" + buffer.toString();
            LlamaServer.sendConsoleLineEvent(null, line);
            LlamaServer.out.println(line);
            buffer.setLength(0);
        } else if (c != '\r') {
            buffer.append(c);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        String text = new String(b, off, len, charset);
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (i > start) buffer.append(text, start, i);
                String line = this.sdf.format(new Date()) + "-" + buffer.toString();
                LlamaServer.sendConsoleLineEvent(null, line);
                LlamaServer.out.println(line);
                buffer.setLength(0);
                start = i + 1;
            } else if (c == '\r') {
                if (i > start) buffer.append(text, start, i);
                start = i + 1;
            }
        }
        if (start < text.length()) buffer.append(text.substring(start));
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            delegate.close();
        }
    }
}
