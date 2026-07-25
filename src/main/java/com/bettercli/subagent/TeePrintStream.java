package com.bettercli.subagent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 把同一份输出同时写入进度流与缓冲流（委托进度透出 + 结果摘录）。
 */
final class TeePrintStream extends PrintStream {

    private TeePrintStream(OutputStream out, Charset charset) {
        super(out, true, charset);
    }

    static TeePrintStream of(PrintStream primary, ByteArrayOutputStream buffer) {
        Objects.requireNonNull(buffer, "buffer");
        PrintStream safePrimary = primary == null
                ? new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
                : primary;
        OutputStream tee = new OutputStream() {
            private final OutputStream buffered = buffer;

            @Override
            public void write(int b) throws IOException {
                safePrimary.write(b);
                buffered.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                safePrimary.write(b, off, len);
                buffered.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                safePrimary.flush();
                buffered.flush();
            }
        };
        return new TeePrintStream(tee, StandardCharsets.UTF_8);
    }
}
