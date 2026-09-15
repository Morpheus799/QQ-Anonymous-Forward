package dev.anonforward;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("QAuxiliary 匿名转发");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView instructions = new TextView(this);
        instructions.setText("1. 在 QAuxiliary → 实验性功能 → 加载外部插件中添加本应用。\n"
                + "2. 填入下方包名和证书 SHA-256，启用后重启 QQ。\n"
                + "3. 在 QQ 中多选消息并点击转发，在‘逐条转发’和‘合并转发’菜单中选择‘匿名转发’。\n\n"
                + "匿名转发只作用于这一次操作；取消联系人选择时会自动撤销。\n"
                + "点击下面的包名或 SHA-256 文本可以直接复制。");
        instructions.setTextSize(16);
        instructions.setPadding(0, padding, 0, padding);
        root.addView(instructions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addCopyableValue(root, "包名", getPackageName(), padding);
        addCopyableValue(root, "证书 SHA-256", certificateSha256(), padding);

        setContentView(root);
    }

    private void addCopyableValue(LinearLayout root, String label, String value, int padding) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, padding / 2, 0, padding / 4);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(15);
        text.setTextIsSelectable(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setPadding(padding / 2, padding / 2, padding / 2, padding / 2);
        text.setContentDescription(label + "，点击复制");
        text.setOnClickListener(view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            Toast.makeText(this, label + "已复制", Toast.LENGTH_SHORT).show();
        });
        root.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String certificateSha256() {
        try {
            PackageInfo info;
            Signature signature;
            if (Build.VERSION.SDK_INT >= 28) {
                info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signature = info.signingInfo.getApkContentsSigners()[0];
            } else {
                info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
                signature = info.signatures[0];
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) {
            return "读取失败：" + e;
        }
    }
}
