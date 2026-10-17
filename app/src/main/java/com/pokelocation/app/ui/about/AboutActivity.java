package com.pokelocation.app.ui.about;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import com.pokelocation.app.R;
import com.pokelocation.app.ui.base.BaseActivity;

public final class AboutActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.about_activity);
        setupToolbar(getString(R.string.about_title), true);
        applyInsets(findViewById(R.id.about_root));
        TextView version = findViewById(R.id.about_version);
        version.setText(getString(R.string.about_version, versionName()));
    }

    private String versionName() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = Build.VERSION.SDK_INT >= 33
                    ? pm.getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                    : pm.getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
