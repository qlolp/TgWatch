package ru.tgwatch.tools;

import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.File;

/** Disposable-emulator fixture: read a live UI without requiring a quiet second. */
@SuppressWarnings("deprecation")
public final class UpgradeUiSnapshot extends UiAutomatorTestCase {
    public void testDumpVisibleWindow() {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        getUiDevice().setCompressedLayoutHeirarchy(true);
        getUiDevice().dumpWindowHierarchy("tgwatch-upgrade-ui.xml");
        File output = new File("/data/local/tmp/tgwatch-upgrade-ui.xml");
        assertTrue("No active UI hierarchy", output.isFile() && output.length() > 0);
    }
}
