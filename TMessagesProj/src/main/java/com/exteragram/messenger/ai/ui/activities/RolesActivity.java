package com.exteragram.messenger.ai.ui.activities;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.ui.components.RoleCell;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AIEditorAlert;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

public class RolesActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_ADD = 0;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_PROMPT_LENGTH = 1024;

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.rolesUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.rolesUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.rolesUpdated && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        actionBar.createMenu().addItem(MENU_ADD, R.drawable.msg_add);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ADD) {
                    showRoleAlert(null);
                }
            }
        });
        fragmentView = view;
        return view;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Roles);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.RolesInfo), "exteraGramPlaceholders", "🎭"));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Suggestions)));
        for (Role role : AiController.getInstance().getSuggestedRoles()) {
            items.add(RoleCell.Factory.asRoleCell(role, v -> selectRole(role)));
        }
        items.add(UItem.asShadow(null));
        List<Role> roles = AiController.getInstance().getRoles();
        if (roles.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.Roles)));
        for (Role role : roles) {
            items.add(RoleCell.Factory.asRoleCell(role, v -> selectRole(role)));
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof Role) {
            Role role = (Role) item.object;
            if (role.isSuggestion()) {
                showRolePreview(role);
            } else {
                showRoleAlert(role);
            }
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item == null || !(item.object instanceof Role)) {
            return false;
        }
        Role role = (Role) item.object;
        if (role.isSuggestion()) {
            return false;
        }
        ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> showRoleAlert(role))
                .add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
                    if (AndroidUtilities.addToClipboard(role.getName() + "\n" + role.getPrompt())) {
                        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                    }
                })
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDeleteRole(role))
                .setScrimViewBackground(listView.getClipBackground(view))
                .setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)
                .show();
        return true;
    }

    private void confirmDeleteRole(Role role) {
        if (role == null || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.Delete));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeleteRoleInfo, role.getName())));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> deleteRole(role));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void deleteRole(Role role) {
        boolean wasSelected = role != null && role.isSelected();
        if (role == null || !AiController.getInstance().removeRole(role)) {
            return;
        }
        if (wasSelected) {
            AiConfig.setSelectedAiRole(AiController.getInstance().getSuggestedRoles().get(0));
        }
        if (listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void selectRole(Role role) {
        if (role.isSelected()) {
            return;
        }
        AiConfig.setSelectedAiRole(role);
        if (listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void showRoleAlert(Role role) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        String name = role != null ? role.getName() : "";
        String prompt = role != null ? role.getPrompt() : "";
        long emojiId = role != null ? role.getEmojiId() : 0;
        boolean editing = role != null && !role.isSuggestion();
        new AIEditorAlert.CreateAiStyleAlert(activity, getResourceProvider())
                .setLocalStyle(name, prompt, emojiId, editing, MAX_NAME_LENGTH, MAX_PROMPT_LENGTH, (newName, newPrompt, newEmojiId) -> {
                    Role newRole = new Role(newName, newPrompt).setEmojiId(newEmojiId != null ? newEmojiId : 0L);
                    boolean wasSelected = role != null && role.isSelected();
                    boolean success;
                    if (role != null && !role.isSuggestion()) {
                        success = AiController.getInstance().updateRole(role, newRole);
                    } else {
                        success = AiController.getInstance().addRole(newRole);
                    }
                    if (success) {
                        if (wasSelected) {
                            AiConfig.setSelectedAiRole(newRole);
                        }
                        getNotificationCenter().postNotificationName(NotificationCenter.rolesUpdated);
                    }
                    return success;
                })
                .show();
    }

    private void showRolePreview(Role role) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        new AIEditorAlert.CreateAiStyleAlert(activity, getResourceProvider())
                .setLocalStylePreview(role.getName(), role.getPrompt(), role.getEmojiId(), MAX_NAME_LENGTH, MAX_PROMPT_LENGTH)
                .show();
    }
}
