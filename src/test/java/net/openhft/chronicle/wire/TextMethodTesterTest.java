package net.openhft.chronicle.wire;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Created by peter on 17/05/2017.
 */
public class TextMethodTesterTest {
    @Test
    public void run() throws IOException {
        TextMethodTester test = new TextMethodTester<>(
                "methods-in.yaml",
                MockMethodsImpl::new,
                MockMethods.class,
                "methods-in.yaml").run();
        assertEquals(test.expected(), test.actual());
    }
}

class MockMethodsImpl implements MockMethods {
    private final MockMethods out;

    public MockMethodsImpl(MockMethods out) {
        this.out = out;
    }

    @Override
    public void method1(MockDto dto) {
        out.method1(dto);
    }

    @Override
    public void method2(MockDto dto) {
        out.method2(dto);
    }
}