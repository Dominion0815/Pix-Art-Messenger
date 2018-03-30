/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package de.pixart.messenger.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.databinding.DataBindingUtil;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.IdRes;
import android.support.v7.app.ActionBar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import net.java.otr4j.session.SessionStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.databinding.ActivityConversationsBinding;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.Conversation;
import de.pixart.messenger.entities.MucOptions;
import de.pixart.messenger.entities.Presence;
import de.pixart.messenger.services.EmojiService;
import de.pixart.messenger.services.UpdateService;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.ui.interfaces.OnConversationArchived;
import de.pixart.messenger.ui.interfaces.OnConversationRead;
import de.pixart.messenger.ui.interfaces.OnConversationSelected;
import de.pixart.messenger.ui.interfaces.OnConversationsListItemUpdated;
import de.pixart.messenger.ui.util.PendingItem;
import de.pixart.messenger.utils.ExceptionHelper;
import de.pixart.messenger.utils.UIHelper;
import de.pixart.messenger.xmpp.OnUpdateBlocklist;
import de.pixart.messenger.xmpp.chatstate.ChatState;

import static de.pixart.messenger.ui.ConversationFragment.REQUEST_DECRYPT_PGP;
import static de.pixart.messenger.ui.SettingsActivity.USE_BUNDLED_EMOJIS;

public class ConversationActivity extends XmppActivity implements OnConversationSelected, OnConversationArchived, OnConversationsListItemUpdated, OnConversationRead, XmppConnectionService.OnAccountUpdate, XmppConnectionService.OnConversationUpdate, XmppConnectionService.OnRosterUpdate, OnUpdateBlocklist, XmppConnectionService.OnShowErrorToast {

    public static final String ACTION_VIEW_CONVERSATION = "de.pixart.messenger.VIEW";
    public static final String EXTRA_CONVERSATION = "conversationUuid";
    public static final String EXTRA_DOWNLOAD_UUID = "de.pixart.messenger.download_uuid";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_NICK = "nick";
    public static final String EXTRA_IS_PRIVATE_MESSAGE = "pm";
    public static final String ACTION_DESTROY_MUC = "de.pixart.messenger.DESTROY_MUC";

    private boolean showLastSeen = false;

    long FirstStartTime = -1;
    String PREF_FIRST_START = "FirstStart";

    //secondary fragment (when holding the conversation, must be initialized before refreshing the overview fragment
    private static final @IdRes
    int[] FRAGMENT_ID_NOTIFICATION_ORDER = {R.id.secondary_fragment, R.id.main_fragment};
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private ActivityConversationsBinding binding;
    private boolean mActivityPaused = true;
    private AtomicBoolean mRedirectInProcess = new AtomicBoolean(false);

    private static boolean isViewIntent(Intent i) {
        return i != null && ACTION_VIEW_CONVERSATION.equals(i.getAction()) && i.hasExtra(EXTRA_CONVERSATION);
    }

    private static Intent createLauncherIntent(Context context) {
        final Intent intent = new Intent(context, ConversationActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    @Override
    protected void refreshUiReal() {
        for(@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            refreshFragment(id);
        }
    }

    @Override
    void onBackendConnected() {
        if (performRedirectIfNecessary(true)) {
            return;
        }
        xmppConnectionService.getNotificationService().setIsInForeground(true);

        final Intent FirstStartIntent = getIntent();
        final Bundle extras = FirstStartIntent.getExtras();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (extras != null && extras.containsKey(PREF_FIRST_START)) {
                FirstStartTime = extras.getLong(PREF_FIRST_START);
                Log.d(Config.LOGTAG, "Get first start time from StartUI: " + FirstStartTime);
            }
        } else {
            FirstStartTime = System.currentTimeMillis();
            Log.d(Config.LOGTAG, "Device is running Android < SDK 23, no restart required: " + FirstStartTime);
        }

        Intent intent = pendingViewIntent.pop();
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (binding.secondaryFragment != null) {
                    notifyFragmentOfBackendConnected(R.id.main_fragment);
                }
                invalidateActionBarTitle();
                return;
            }
        }

        if (FirstStartTime == 0) {
            Log.d(Config.LOGTAG, "First start time: " + FirstStartTime + ", restarting App");
            //write first start timestamp to file
            FirstStartTime = System.currentTimeMillis();
            SharedPreferences FirstStart = getApplicationContext().getSharedPreferences(PREF_FIRST_START, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = FirstStart.edit();
            editor.putLong(PREF_FIRST_START, FirstStartTime);
            editor.commit();
            // restart
            Intent restartintent = getBaseContext().getPackageManager().getLaunchIntentForPackage(getBaseContext().getPackageName());
            restartintent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            restartintent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(restartintent);
            System.exit(0);
        }

        if (xmppConnectionService.getAccounts().size() != 0) {
            if (xmppConnectionService.hasInternetConnection()) {
                if (xmppConnectionService.isWIFI() || (xmppConnectionService.isMobile() && !xmppConnectionService.isMobileRoaming())) {
                    if (!xmppConnectionService.installedFromFDroid()) {
                        AppUpdate(xmppConnectionService.installedFromPlayStore());
                    }
                }
            }
        }

        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            notifyFragmentOfBackendConnected(id);
        }
        invalidateActionBarTitle();
        if (binding.secondaryFragment != null && ConversationFragment.getConversation(this) == null) {
            Conversation conversation = ConversationsOverviewFragment.getSuggestion(this);
            if (conversation != null) {
                openConversation(conversation, null);
            }
        }
        showDialogsIfMainIsOverview();
    }

    private boolean performRedirectIfNecessary(boolean noAnimation) {
        return performRedirectIfNecessary(null, noAnimation);
    }

    private boolean performRedirectIfNecessary(final Conversation ignore, final boolean noAnimation) {
        if (xmppConnectionService == null) {
            return false;
        }
        boolean isConversationsListEmpty = xmppConnectionService.isConversationsListEmpty(ignore);
        if (isConversationsListEmpty && mRedirectInProcess.compareAndSet(false, true)) {
            final Intent intent = getRedirectionIntent(noAnimation);
            runOnUiThread(() -> {
                startActivity(intent);
                if (noAnimation) {
                    overridePendingTransition(0, 0);
                }
            });
        }
        return mRedirectInProcess.get();
    }

    private Intent getRedirectionIntent(boolean noAnimation) {
        Account pendingAccount = xmppConnectionService.getPendingAccount();
        Intent intent;
        if (pendingAccount != null) {
            intent = new Intent(this, EditAccountActivity.class);
            intent.putExtra("jid", pendingAccount.getJid().toBareJid().toString());
        } else {
            if (xmppConnectionService.getAccounts().size() == 0) {
                if (Config.X509_VERIFICATION) {
                    intent = new Intent(this, ManageAccountActivity.class);
                } else if (Config.MAGIC_CREATE_DOMAIN != null) {
                    intent = new Intent(this, WelcomeActivity.class);
                    WelcomeActivity.addInviteUri(intent, getIntent());
                } else {
                    intent = new Intent(this, EditAccountActivity.class);
                }
            } else {
                intent = new Intent(this, StartConversationActivity.class);
            }
        }
        intent.putExtra("init", true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (noAnimation) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        return intent;
    }

    private void showDialogsIfMainIsOverview() {
        Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment != null && fragment instanceof ConversationsOverviewFragment) {
            if (ExceptionHelper.checkForCrash(this, this.xmppConnectionService)) {
                return;
            }
            openBatteryOptimizationDialogIfNeeded();
        }
    }

    private String getBatteryOptimizationPreferenceKey() {
        @SuppressLint("HardwareIds") String device = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return "show_battery_optimization" + (device == null ? "" : device);
    }

    private void setNeverAskForBatteryOptimizationsAgain() {
        getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply();
    }

    private void openBatteryOptimizationDialogIfNeeded() {
        if (hasAccountWithoutPush()
                && isOptimizingBattery()
                && getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.battery_optimizations_enabled);
            builder.setMessage(R.string.battery_optimizations_enabled_dialog);
            builder.setPositiveButton(R.string.next, (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                Uri uri = Uri.parse("package:" + getPackageName());
                intent.setData(uri);
                try {
                    startActivityForResult(intent, REQUEST_BATTERY_OP);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.device_does_not_support_battery_op, Toast.LENGTH_SHORT).show();
                }
            });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                builder.setOnDismissListener(dialog -> setNeverAskForBatteryOptimizationsAgain());
            }
            AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
    }

    private boolean hasAccountWithoutPush() {
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE && !xmppConnectionService.getPushManagementService().available(account)) {
                return true;
            }
        }
        return false;
    }


    private void notifyFragmentOfBackendConnected(@IdRes int id) {
        final Fragment fragment = getFragmentManager().findFragmentById(id);
        if (fragment != null && fragment instanceof XmppFragment) {
            ((XmppFragment) fragment).onBackendConnected();
        }
    }

    private void refreshFragment(@IdRes int id) {
        final Fragment fragment = getFragmentManager().findFragmentById(id);
        if (fragment != null && fragment instanceof XmppFragment) {
            ((XmppFragment) fragment).refresh();
        }
    }

    private boolean processViewIntent(Intent intent) {
        String uuid = intent.getStringExtra(EXTRA_CONVERSATION);
        Conversation conversation = uuid != null ? xmppConnectionService.findConversationByUuid(uuid) : null;
        if (conversation == null) {
            Log.d(Config.LOGTAG, "unable to view conversation with uuid:" + uuid);
            return false;
        }
        openConversation(conversation, intent.getExtras());
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.d(Config.LOGTAG, "on activity result");
        if (resultCode == RESULT_OK) {
            handlePositiveActivityResult(requestCode, data);
        } else {
            handleNegativeActivityResult(requestCode);
        }
    }

    private void handleNegativeActivityResult(int requestCode) {
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                Conversation conversation = ConversationFragment.getConversationReliable(this);
                if (conversation == null) {
                    break;
                }
                conversation.getAccount().getPgpDecryptionService().giveUpCurrentDecryption();
                break;
            case REQUEST_BATTERY_OP:
                setNeverAskForBatteryOptimizationsAgain();
                break;
        }
    }

    private void handlePositiveActivityResult(int requestCode, final Intent data) {
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                Conversation conversation = ConversationFragment.getConversationReliable(this);
                if (conversation == null) {
                    break;
                }
                conversation.getAccount().getPgpDecryptionService().continueDecryption(data);
                break;
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new EmojiService(this).init(useBundledEmoji());
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations);
        this.getFragmentManager().addOnBackStackChangedListener(this::invalidateActionBarTitle);
        this.getFragmentManager().addOnBackStackChangedListener(this::showDialogsIfMainIsOverview);
        this.initializeFragments();
        this.invalidateActionBarTitle();
        final Intent intent = getIntent();
        if (isViewIntent(intent)) {
            pendingViewIntent.push(intent);
            setIntent(createLauncherIntent(this));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_conversations, menu);
        MenuItem qrCodeScanMenuItem = menu.findItem(R.id.action_scan_qr_code);
        if (qrCodeScanMenuItem != null) {
            Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
            boolean visible = getResources().getBoolean(R.bool.show_qr_code_scan)
                    && fragment != null
                    && fragment instanceof ConversationsOverviewFragment;
            qrCodeScanMenuItem.setVisible(visible);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onConversationSelected(Conversation conversation) {
        openConversation(conversation, null);
    }

    private void openConversation(Conversation conversation, Bundle extras) {
        ConversationFragment conversationFragment = (ConversationFragment) getFragmentManager().findFragmentById(R.id.secondary_fragment);
        final boolean mainNeedsRefresh;
        if (conversationFragment == null) {
            mainNeedsRefresh = false;
            Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
            if (mainFragment != null && mainFragment instanceof ConversationFragment) {
                conversationFragment = (ConversationFragment) mainFragment;
            } else {
                conversationFragment = new ConversationFragment();
                FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
                fragmentTransaction.replace(R.id.main_fragment, conversationFragment);
                fragmentTransaction.addToBackStack(null);
                fragmentTransaction.commit();
            }
        } else {
            mainNeedsRefresh = true;
        }
        conversationFragment.reInit(conversation, extras);
        if (mainNeedsRefresh) {
            refreshFragment(R.id.main_fragment);
        } else {
            invalidateActionBarTitle();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                FragmentManager fm = getFragmentManager();
                if (fm.getBackStackEntryCount() > 0) {
                    fm.popBackStack();
                    return true;
                }
                break;
            case R.id.action_scan_qr_code:
                UriHandlerActivity.scan(this);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }
        mRedirectInProcess.set(false);
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        this.showLastSeen = preferences.getBoolean("last_activity", false);
        super.onStart();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        if (isViewIntent(intent)) {
            if (xmppConnectionService != null) {
                processViewIntent(intent);
            } else {
                pendingViewIntent.push(intent);
            }
        } else if (intent != null && ACTION_DESTROY_MUC.equals(intent.getAction())) {
            final Bundle extras = intent.getExtras();
            if (extras != null && extras.containsKey("MUC_UUID")) {
                Log.d(Config.LOGTAG, "Get " + intent.getAction() + " intent for " + extras.getString("MUC_UUID"));
                Conversation conversation = xmppConnectionService.findConversationByUuid(extras.getString("MUC_UUID"));
                ConversationActivity.this.xmppConnectionService.clearConversationHistory(conversation);
                xmppConnectionService.destroyMuc(conversation);
                endConversation(conversation);
            }
        }
    }

    public void endConversation(Conversation conversation) {
        xmppConnectionService.archiveConversation(conversation);
        onConversationArchived(conversation);
    }

    @Override
    public void onPause() {
        this.mActivityPaused = true;
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        this.mActivityPaused = false;
    }

    private void initializeFragments() {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        Fragment secondaryFragment = getFragmentManager().findFragmentById(R.id.secondary_fragment);
        if (mainFragment != null) {
            Log.d(Config.LOGTAG, "initializeFragment(). main fragment exists");
            if (binding.secondaryFragment != null) {
                if (mainFragment instanceof ConversationFragment) {
                    Log.d(Config.LOGTAG, "gained secondary fragment. moving...");
                    getFragmentManager().popBackStack();
                    transaction.remove(mainFragment);
                    transaction.commit();
                    getFragmentManager().executePendingTransactions();
                    transaction = getFragmentManager().beginTransaction();
                    transaction.replace(R.id.secondary_fragment, mainFragment);
                    transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
                    transaction.commit();
                    return;
                }
            } else {
                if (secondaryFragment != null && secondaryFragment instanceof ConversationFragment) {
                    Log.d(Config.LOGTAG, "lost secondary fragment. moving...");
                    transaction.remove(secondaryFragment);
                    transaction.commit();
                    getFragmentManager().executePendingTransactions();
                    transaction = getFragmentManager().beginTransaction();
                    transaction.replace(R.id.main_fragment, secondaryFragment);
                    transaction.addToBackStack(null);
                    transaction.commit();
                    return;
                }
            }
        } else {
            transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
        }

        if (binding.secondaryFragment != null && secondaryFragment == null) {
            transaction.replace(R.id.secondary_fragment, new ConversationFragment());
        }
        transaction.commit();
    }

    private void invalidateActionBarTitle() {
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
            if (mainFragment != null && mainFragment instanceof ConversationFragment) {
                final Conversation conversation = ((ConversationFragment) mainFragment).getConversation();
                if (conversation != null) {
                    actionBar.setDisplayHomeAsUpEnabled(true);
                    View view = getLayoutInflater().inflate(R.layout.ab_title, null);
                    getSupportActionBar().setCustomView(view);
                    actionBar.setDisplayShowTitleEnabled(false);
                    actionBar.setDisplayShowCustomEnabled(true);
                    TextView abtitle = findViewById(android.R.id.text1);
                    TextView absubtitle = findViewById(android.R.id.text2);
                    abtitle.setText(conversation.getName());
                    abtitle.setOnClickListener(view1 -> {
                        if (conversation.getMode() == Conversation.MODE_SINGLE) {
                            switchToContactDetails(conversation.getContact());
                        } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                            Intent intent = new Intent(ConversationActivity.this, ConferenceDetailsActivity.class);
                            intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                            intent.putExtra("uuid", conversation.getUuid());
                            startActivity(intent);
                        }
                    });
                    abtitle.setSelected(true);
                    if (conversation.getMode() == Conversation.MODE_SINGLE && !conversation.withSelf()) {
                        ChatState state = conversation.getIncomingChatState();
                        if (conversation.getContact().getShownStatus() == Presence.Status.OFFLINE) {
                            absubtitle.setText(getString(R.string.account_status_offline));
                            absubtitle.setSelected(true);
                            absubtitle.setOnClickListener(view12 -> {
                                if (conversation.getMode() == Conversation.MODE_SINGLE) {
                                    switchToContactDetails(conversation.getContact());
                                } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                    Intent intent = new Intent(ConversationActivity.this, ConferenceDetailsActivity.class);
                                    intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                                    intent.putExtra("uuid", conversation.getUuid());
                                    startActivity(intent);
                                }
                            });
                        } else {
                            if (state == ChatState.COMPOSING) {
                                absubtitle.setText(getString(R.string.is_typing));
                                absubtitle.setTypeface(null, Typeface.BOLD_ITALIC);
                                absubtitle.setSelected(true);
                                absubtitle.setOnClickListener(view13 -> {
                                    if (conversation.getMode() == Conversation.MODE_SINGLE) {
                                        switchToContactDetails(conversation.getContact());
                                    } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                        Intent intent = new Intent(ConversationActivity.this, ConferenceDetailsActivity.class);
                                        intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                                        intent.putExtra("uuid", conversation.getUuid());
                                        startActivity(intent);
                                    }
                                });
                            } else {
                                if (showLastSeen && conversation.getContact().getLastseen() > 0) {
                                    absubtitle.setText(UIHelper.lastseen(getApplicationContext(), conversation.getContact().isActive(), conversation.getContact().getLastseen()));
                                } else {
                                    absubtitle.setText(getString(R.string.account_status_online));
                                }
                                absubtitle.setSelected(true);
                                absubtitle.setOnClickListener(view14 -> {
                                    if (conversation.getMode() == Conversation.MODE_SINGLE) {
                                        switchToContactDetails(conversation.getContact());
                                    } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                        Intent intent = new Intent(ConversationActivity.this, ConferenceDetailsActivity.class);
                                        intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                                        intent.putExtra("uuid", conversation.getUuid());
                                        startActivity(intent);
                                    }
                                });
                            }
                        }
                    } else {
                        if (conversation.getParticipants() != null) {
                            ChatState state = ChatState.COMPOSING;
                            List<MucOptions.User> userWithChatStates = conversation.getMucOptions().getUsersWithChatState(state, 5);
                            if (userWithChatStates.size() == 0) {
                                state = ChatState.PAUSED;
                                userWithChatStates = conversation.getMucOptions().getUsersWithChatState(state, 5);
                            }
                            List<MucOptions.User> users = conversation.getMucOptions().getUsers(true);
                            if (state == ChatState.COMPOSING) {
                                if (userWithChatStates.size() > 0) {
                                    if (userWithChatStates.size() == 1) {
                                        MucOptions.User user = userWithChatStates.get(0);
                                        absubtitle.setText(getString(R.string.contact_is_typing, UIHelper.getDisplayName(user)));
                                    } else {
                                        StringBuilder builder = new StringBuilder();
                                        for (MucOptions.User user : userWithChatStates) {
                                            if (builder.length() != 0) {
                                                builder.append(", ");
                                            }
                                            builder.append(UIHelper.getDisplayName(user));
                                        }
                                        absubtitle.setText(getString(R.string.contacts_are_typing, builder.toString()));
                                    }
                                }
                            } else {
                                if (users.size() == 1) {
                                    absubtitle.setText(getString(R.string.one_participant));
                                } else {
                                    absubtitle.setText(getString(R.string.more_participants, users.size()));
                                }
                            }
                            absubtitle.setSelected(true);
                            absubtitle.setOnClickListener(view15 -> {
                                if (conversation.getMode() == Conversation.MODE_SINGLE) {
                                    switchToContactDetails(conversation.getContact());
                                } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                    Intent intent = new Intent(ConversationActivity.this, ConferenceDetailsActivity.class);
                                    intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                                    intent.putExtra("uuid", conversation.getUuid());
                                    startActivity(intent);
                                }
                            });
                        } else {
                            absubtitle.setText(R.string.no_participants);
                            abtitle.setSelected(true);
                            absubtitle.setOnClickListener(view16 -> {
                                if (conversation.getMode() == Conversation.MODE_SINGLE) {
                                    switchToContactDetails(conversation.getContact());
                                } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                    Intent intent = new Intent(ConversationActivity.this, ConferenceDetailsActivity.class);
                                    intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
                                    intent.putExtra("uuid", conversation.getUuid());
                                    startActivity(intent);
                                }
                            });
                        }
                    }
                    return;
                }
            }
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setDisplayShowCustomEnabled(false);
            actionBar.setTitle(R.string.app_name);
            actionBar.setSubtitle(null);
            actionBar.setDisplayHomeAsUpEnabled(false);
        }
    }

    public boolean useBundledEmoji() {
        return getPreferences().getBoolean(USE_BUNDLED_EMOJIS, getResources().getBoolean(R.bool.use_bundled_emoji));
    }

    public void verifyOtrSessionDialog(final Conversation conversation, View view) {
        if (!conversation.hasValidOtrSession() || conversation.getOtrSession().getSessionStatus() != SessionStatus.ENCRYPTED) {
            Toast.makeText(this, R.string.otr_session_not_started, Toast.LENGTH_LONG).show();
            return;
        }
        if (view == null) {
            return;
        }
        PopupMenu popup = new PopupMenu(this, view);
        popup.inflate(R.menu.verification_choices);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                Intent intent = new Intent(ConversationActivity.this, VerifyOTRActivity.class);
                intent.setAction(VerifyOTRActivity.ACTION_VERIFY_CONTACT);
                intent.putExtra("contact", conversation.getContact().getJid().toBareJid().toString());
                intent.putExtra(EXTRA_ACCOUNT, conversation.getAccount().getJid().toBareJid().toString());
                switch (menuItem.getItemId()) {
                    case R.id.scan_fingerprint:
                        intent.putExtra("mode", VerifyOTRActivity.MODE_SCAN_FINGERPRINT);
                        break;
                    case R.id.ask_question:
                        intent.putExtra("mode", VerifyOTRActivity.MODE_ASK_QUESTION);
                        break;
                    case R.id.manual_verification:
                        intent.putExtra("mode", VerifyOTRActivity.MODE_MANUAL_VERIFICATION);
                        break;
                }
                startActivity(intent);
                return true;
            }
        });
        popup.show();
    }

    @Override
    public void onConversationArchived(Conversation conversation) {
        if (performRedirectIfNecessary(conversation, false)) {
            return;
        }
        Fragment mainFragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (mainFragment != null && mainFragment instanceof ConversationFragment) {
            getFragmentManager().popBackStack();
            return;
        }
        Fragment secondaryFragment = getFragmentManager().findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment != null && secondaryFragment instanceof ConversationFragment) {
            if (((ConversationFragment) secondaryFragment).getConversation() == conversation) {
                Conversation suggestion = ConversationsOverviewFragment.getSuggestion(this, conversation);
                if (suggestion != null) {
                    openConversation(suggestion, null);
                    return;
                }
            }
        }
    }

    @Override
    public void onConversationsListItemUpdated() {
        Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment != null && fragment instanceof ConversationsOverviewFragment) {
            ((ConversationsOverviewFragment) fragment).refresh();
        }
    }

    @Override
    public void onConversationRead(Conversation conversation) {
        if (!mActivityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation);
        }
    }

    @Override
    public void onAccountUpdate() {
        this.refreshUi();
    }

    @Override
    public void onConversationUpdate() {
        if (performRedirectIfNecessary(false)) {
            return;
        }
        this.refreshUi();
    }

    @Override
    public void onRosterUpdate() {
        this.refreshUi();
    }

    @Override
    public void OnUpdateBlocklist(OnUpdateBlocklist.Status status) {
        this.refreshUi();
    }

    @Override
    public void onShowErrorToast(int resId) {
        runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }

    protected void AppUpdate(boolean PlayStore) {
        if (PlayStore) {
            return;
        }
        String PREFS_NAME = "UpdateTimeStamp";
        SharedPreferences UpdateTimeStamp = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastUpdateTime = UpdateTimeStamp.getLong("lastUpdateTime", 0);
        Log.d(Config.LOGTAG, "AppUpdater: LastUpdateTime: " + lastUpdateTime);
        if ((lastUpdateTime + (Config.UPDATE_CHECK_TIMER * 1000)) < System.currentTimeMillis()) {
            lastUpdateTime = System.currentTimeMillis();
            SharedPreferences.Editor editor = UpdateTimeStamp.edit();
            editor.putLong("lastUpdateTime", lastUpdateTime);
            editor.apply();
            Log.d(Config.LOGTAG, "AppUpdater: CurrentTime: " + lastUpdateTime);
            if (!installFromUnknownSourceAllowed() && !PlayStore) {
                openInstallFromUnknownSourcesDialogIfNeeded();
            } else {
                UpdateService task = new UpdateService(this, PlayStore);
                task.executeOnExecutor(UpdateService.THREAD_POOL_EXECUTOR, "false");
                Log.d(Config.LOGTAG, "AppUpdater started");
            }
        } else {
            Log.d(Config.LOGTAG, "AppUpdater stopped");
            return;
        }
    }
}