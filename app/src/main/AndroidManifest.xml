<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@drawable/app_icon"
        android:label="@string/app_name"
        android:supportsRtl="true">

        <!-- ===== Xposed / LSPosed module markers ===== -->
        <meta-data
            android:name="xposedmodule"
            android:value="true" />
        <meta-data
            android:name="xposeddescription"
            android:value="@string/xposed_description" />
        <!-- 53 = min usable Xposed API; LSPosed satisfies this -->
        <meta-data
            android:name="xposedminversion"
            android:value="53" />
        <!-- Scope: we only ever want to load into the system framework -->
        <meta-data
            android:name="xposedscope"
            android:resource="@array/xposed_scope" />

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".AboutActivity"
            android:exported="false" />
    </application>

</manifest>
