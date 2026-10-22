package ru.tgwatch.testui;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import org.xmlpull.v1.XmlSerializer;

/** Self-targeted, read-only test instrumentation; no account text or arbitrary UI is exported. */
public final class SnapshotInstrumentation extends Instrumentation {
    private static final List<String> IDS = Arrays.asList("ru.tgwatch:id/btnOnboardingSkip",
            "ru.tgwatch:id/btnToggle", "ru.tgwatch:id/mainScroll");
    private int remaining = 2_000;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            UiAutomation automation = getUiAutomation();
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) throw new IllegalStateException("No active window");
            StringWriter writer = new StringWriter();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(writer);
            xml.startTag(null, "hierarchy");
            collect(root, xml, 0);
            xml.endTag(null, "hierarchy");
            xml.flush();
            result.putString("controls", writer.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("snapshot_error", error.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void collect(AccessibilityNodeInfo node, XmlSerializer xml, int depth) throws Exception {
        if (remaining-- <= 0 || depth > 64) throw new IllegalStateException("Window budget exceeded");
        try {
            String id = node.getViewIdResourceName();
            if (IDS.contains(id) && "ru.tgwatch".equals(String.valueOf(node.getPackageName())) && node.isVisibleToUser()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                xml.startTag(null, "node");
                xml.attribute(null, "package", "ru.tgwatch");
                xml.attribute(null, "resource-id", id);
                xml.attribute(null, "bounds", "[" + bounds.left + "," + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]");
                xml.attribute(null, "enabled", Boolean.toString(node.isEnabled()));
                xml.attribute(null, "clickable", Boolean.toString(node.isClickable()));
                xml.endTag(null, "node");
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) collect(child, xml, depth + 1);
            }
        } finally { node.recycle(); }
    }
}
