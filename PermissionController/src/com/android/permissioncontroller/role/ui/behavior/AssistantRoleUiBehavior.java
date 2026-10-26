/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.permissioncontroller.role.ui.behavior;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.text.Html;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.permission.flags.Flags;
import com.android.permissioncontroller.R;
import com.android.permissioncontroller.permission.utils.CollectionUtils;
import com.android.permissioncontroller.permission.utils.Utils;
import com.android.permissioncontroller.role.ui.RoleApplicationItem;
import com.android.permissioncontroller.role.ui.TwoTargetPreference;
import com.android.permissioncontroller.role.utils.PackageUtils;
import com.android.role.controller.model.Role;
import com.android.role.controller.util.SignedPackage;
import com.android.role.controller.util.SignedPackageUtils;
import com.android.role.controller.util.UserUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

/***
 * Class for UI behavior of Assistant role
 */
public class AssistantRoleUiBehavior implements RoleUiBehavior {

    private static final String LOG_TAG = AssistantRoleUiBehavior.class.getSimpleName();

    @Override
    public void preparePreferenceAsUser(@NonNull Role role, @NonNull TwoTargetPreference preference,
            @NonNull List<ApplicationInfo> applicationInfos, @NonNull UserHandle user,
            @NonNull Context context) {
        Intent intent = getSettingsIntentAsUser(role, user, context);
        TwoTargetPreference.OnSecondTargetClickListener listener = null;
        if (intent != null) {
            listener = preference2 -> {
                try {
                    context.startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Log.e(LOG_TAG, "Cannot start activity for assistant settings", e);
                }
            };
        }
        preference.setOnSecondTargetClickListener(listener);
    }

    // The settings activity declared in the holder's voice interaction metadata, as the old
    // Settings assist screen offered it.
    @Nullable
    private static Intent getSettingsIntentAsUser(@NonNull Role role, @NonNull UserHandle user,
            @NonNull Context context) {
        RoleManager roleManager = context.getSystemService(RoleManager.class);
        String packageName = CollectionUtils.firstOrNull(roleManager.getRoleHoldersAsUser(
                role.getName(), user));
        if (packageName == null) {
            return null;
        }
        PackageManager userPackageManager = UserUtils.getUserContext(context, user)
                .getPackageManager();
        List<ResolveInfo> services = userPackageManager.queryIntentServices(
                new Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(packageName),
                PackageManager.GET_META_DATA);
        for (ResolveInfo service : services) {
            String activity = getSettingsActivity(userPackageManager, service.serviceInfo);
            if (TextUtils.isEmpty(activity)) {
                continue;
            }
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName.createRelative(packageName, activity))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ResolveInfo resolveInfo = userPackageManager.resolveActivity(intent, 0);
            if (resolveInfo != null && resolveInfo.activityInfo != null
                    && resolveInfo.activityInfo.exported) {
                return intent;
            }
        }
        return null;
    }

    @Nullable
    private static String getSettingsActivity(@NonNull PackageManager packageManager,
            @NonNull ServiceInfo serviceInfo) {
        if (!Manifest.permission.BIND_VOICE_INTERACTION.equals(serviceInfo.permission)) {
            return null;
        }
        try (XmlResourceParser parser = serviceInfo.loadXmlMetaData(packageManager,
                VoiceInteractionService.SERVICE_META_DATA)) {
            if (parser == null) {
                return null;
            }
            int type;
            do {
                type = parser.next();
            } while (type != XmlResourceParser.END_DOCUMENT && type != XmlResourceParser.START_TAG);

            String settingsActivity = null;
            boolean supportsAssist = false;
            AttributeSet attrs = Xml.asAttributeSet(parser);
            int numAttrs = attrs.getAttributeCount();
            for (int i = 0; i < numAttrs; i++) {
                switch (attrs.getAttributeNameResource(i)) {
                    case android.R.attr.settingsActivity:
                        settingsActivity = attrs.getAttributeValue(i);
                        break;
                    case android.R.attr.supportsAssist:
                        supportsAssist = attrs.getAttributeBooleanValue(i, false);
                        break;
                }
            }
            return supportsAssist ? settingsActivity : null;
        } catch (XmlPullParserException | IOException | Resources.NotFoundException e) {
            return null;
        }
    }

    @Nullable
    @Override
    public Intent getManageIntentAsUser(@NonNull Role role, @NonNull UserHandle user,
            @NonNull Context context) {
        PackageManager packageManager = context.getPackageManager();
        boolean isAutomotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
        if (isAutomotive) {
            return null;
        }
        if (android.permission.flags.Flags.assistSettingsPrivacyImprovementsEnabled()
                && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                && !packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            return null;
        }
        return new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
    }

    @Nullable
    @Override
    public String getRecommendedApplicationsTitle(@NonNull Role role, @NonNull Context context) {
        if (Flags.defaultAppsRecommendationEnabled()) {
            String by = context.getString(R.string.config_recommendedAssistantsBy);
            if (!by.isEmpty()) {
                return context.getString(R.string.role_assistant_recommended_title, by);
            }
        }
        return RoleUiBehavior.super.getRecommendedApplicationsTitle(role, context);
    }

    @Nullable
    @Override
    public String getRecommendedApplicationsDescription(@NonNull Role role,
            @NonNull Context context) {
        if (Flags.defaultAppsRecommendationEnabled()) {
            String description =
                    context.getString(R.string.config_recommendedAssistantsDescription);
            if (!description.isEmpty()) {
                return description;
            }
        }
        return RoleUiBehavior.super.getRecommendedApplicationsDescription(role, context);
    }

    @NonNull
    @Override
    public Predicate<RoleApplicationItem> getRecommendedApplicationFilter(
            @NonNull Role role, @NonNull Context context) {
        if (Flags.defaultAppsRecommendationEnabled()
                && getRecommendedApplicationsTitle(role, context) != null
                && getRecommendedApplicationsDescription(role, context) != null) {
            List<SignedPackage> signedPackages = SignedPackage.parseList(
                    context.getResources().getString(R.string.config_recommendedAssistants));
            return applicationItem -> SignedPackageUtils.matchesAny(
                    applicationItem.getApplicationInfo(), signedPackages, context);
        }
        return RoleUiBehavior.super.getRecommendedApplicationFilter(role, context);
    }

    @Nullable
    @Override
    public ConfirmationDialogInfo getConfirmationDialogInfo(@NonNull Role role,
            @NonNull String packageName, @NonNull UserHandle user, @NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                && android.permission.flags.Flags.newDefaultAppConfirmationDialogEnabled()
                && android.permission.flags.Flags.assistSettingsPrivacyImprovementsEnabled()) {
            ApplicationInfo applicationInfo =
                    PackageUtils.getApplicationInfoAsUser(packageName, user, context);
            String appLabel = applicationInfo != null ? Utils.getFullAppLabel(applicationInfo,
                    context) : packageName;
            String escapedAppLabel = Html.escapeHtml(appLabel);
            CharSequence message;
            if (isReadScreenContextAllowed(packageName, user, context)) {
                message = Html.fromHtml(context.getString(
                        R.string.assistant_confirmation_message_access_restored_v37,
                        escapedAppLabel));
            } else {
                message = context.getString(R.string.assistant_confirmation_message_v37);
            }
            return new ConfirmationDialogInfo(true,
                    Html.fromHtml(context.getString(R.string.assistant_confirmation_title_v37,
                            escapedAppLabel)),
                    message,
                    context.getString(R.string.default_app_confirmation_positive_button),
                    context.getString(R.string.default_app_confirmation_negative_button), true);
        } else {
            return new ConfirmationDialogInfo(null,
                    context.getString(R.string.assistant_confirmation_message),
                    context.getString(android.R.string.ok),
                    context.getString(android.R.string.cancel), true);
        }
    }

    private static boolean isReadScreenContextAllowed(@NonNull String packageName,
            @NonNull UserHandle user, @NonNull Context context) {
        Context userContext = UserUtils.getUserContext(context, user);
        PackageManager userPackageManager = userContext.getPackageManager();
        int uid;
        try {
            uid = userPackageManager.getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        AppOpsManager appOpsManager = userContext.getSystemService(AppOpsManager.class);
        return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_READ_SCREEN_CONTEXT, uid,
                packageName) == AppOpsManager.MODE_ALLOWED;
    }
}
