// Source contracts for the glass-header entry point. These do not simulate Android taps.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const chat = readFileSync(new URL('../../TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java', import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const avatar = readFileSync(new URL('../../TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java', import.meta.url), 'utf8').replaceAll('\r\n', '\n');

function between(source, start, end) {
    const from = source.indexOf(start);
    assert.ok(from >= 0, `Missing start: ${start}`);
    const to = source.indexOf(end, from + start.length);
    assert.ok(to > from, `Missing end: ${end}`);
    return source.slice(from, to);
}

function assertNativeMenuRouting(source) {
    const click = between(source, '} else if (id == liquid_header_avatar) {', '} else if (id == view_as_topics) {');
    assert.equal(click.slice(click.indexOf('{') + 1).trim(), 'showLiquidHeaderChatMenu();');
}

function assertNativeMenuButton(source) {
    const layout = between(source, 'private void updateLiquidGlassHeaderLayout(boolean enabled)', 'private void updateLiquidHeaderAvatar()');
    assert.match(layout, /liquidHeaderAvatarItem\.setEnabled\(headerItem != null && headerItem\.hasSubMenu\(\)\)/);
    assert.doesNotMatch(layout, /isAvatarClickable/);
    const creation = between(source, 'liquidHeaderAvatarItem = menu.addItem(liquid_header_avatar, 0);', 'menu.setVisibility(inMenuMode');
    assert.match(creation, /setContentDescription\(getString\(R\.string\.AccDescrMoreOptions\)\)/);
}

function nativeMenuHelper(source) {
    return between(source, 'private void showLiquidHeaderChatMenu()', 'private void updateLiquidHeaderForSearch(boolean expanded)');
}

const nativeMenuGuards = [
    'isFinished',
    'getParentActivity() == null',
    'currentAccount != UserConfig.selectedAccount',
    'SharedConfig.appLocked',
    'SharedConfig.isWaitingForPasscodeEnter',
    'org.telegram.messenger.BlackHoleVault.blocks(currentAccount, dialog_id)',
    'actionBar == null',
    'actionBar.isActionModeShowed()',
    'headerItem == null',
    '!headerItem.hasSubMenu()',
    'liquidHeaderAvatarItem == null',
    'liquidHeaderAvatarItem.getVisibility() != View.VISIBLE',
];

function assertNativeMenuHelper(source) {
    const helper = nativeMenuHelper(source);
    // Both early-return guards must precede the only menu-opening call.
    const guarded = between(helper, 'if (', '// Reuse the actual overflow contents');
    for (const condition of nativeMenuGuards) {
        assert.ok(guarded.includes(condition), `Missing menu restriction: ${condition}`);
    }
    assert.match(guarded, /getVisibility\(\) != View\.VISIBLE\) return;/);
    assert.match(guarded, /if \(actionBar\.getActionBarMenuOnItemClick\(\) != null\s*&& !actionBar\.getActionBarMenuOnItemClick\(\)\.canOpenMenu\(\)\) return;/);
    assert.match(helper, /headerItem\.toggleSubMenu\(null, liquidHeaderAvatarItem\);/);
    assert.equal((helper.match(/toggleSubMenu\(/g) || []).length, 1);
    // Protected-message restrictions belong to the tools action, not the native menu.
    assert.doesNotMatch(helper, /showLunagramChatTools|lunagramToolsContext|openProfile|performExternalAvatarClick|new ActionBarPopupWindow|currentEncryptedChat|isPeerNoForwards/);
}

function replaceChecked(source, oldText, newText) {
    assert.ok(source.includes(oldText), `Mutation target not found: ${oldText}`);
    const changed = source.replace(oldText, newText);
    assert.notEqual(changed, source, 'Negative control did not change the source');
    return changed;
}

test('right avatar routes only to the existing native chat menu', () => {
    assertNativeMenuRouting(chat);
    assertNativeMenuHelper(chat);
});

test('avatar menu availability is independent of profile availability and has the overflow label', () => {
    assertNativeMenuButton(chat);
});

test('the right avatar retains its image, circular size and glass slot', () => {
    const creation = between(chat, 'liquidHeaderAvatarItem = menu.addItem(liquid_header_avatar, 0);', 'menu.setVisibility(inMenuMode');
    assert.match(creation, /getIconView\(\)\.setVisibility\(View\.GONE\)/);
    assert.match(creation, /liquidHeaderAvatarView = new BackupImageView\(context\)/);
    assert.match(creation, /liquidHeaderAvatarView\.setRoundRadius\(dp\(21\)\)/);
    assert.match(creation, /addView\(liquidHeaderAvatarView, LayoutHelper\.createFrame\(42, 42, Gravity\.CENTER\)\)/);
    assert.match(chat, /menu\.setExclusiveItem\(liquidHeaderAvatarItem, liquidHeaderAvatarView\)/);
    assert.match(chat, /avatarContainer\.bindAvatarTo\(liquidHeaderAvatarView, liquidHeaderAvatarDrawable\)/);
});

test('center oval click and touch retain the existing profile navigation', () => {
    const click = between(avatar, 'if ((!parentFragment.isThreadChat()', 'TLRPC.Chat chat = parentFragment.getCurrentChat();');
    assert.match(click, /setOnClickListener\(v -> \{/);
    assert.match(click, /openProfile\(false, !externalAvatarMode, false\)/);
    const touch = between(avatar, 'public boolean onTouchEvent(MotionEvent ev)', 'public void setPressed(boolean pressed)');
    assert.match(touch, /openProfile\(false, !externalAvatarMode, false\)/);
    assert.doesNotMatch(click + touch, /showLunagramChatTools|showLiquidHeaderChatMenu|toggleSubMenu/);
});

test('Lunagram tools remains a native menu item with its existing security guards', () => {
    assert.match(chat, /headerItem\.lazilyAddSubItem\(97104, R\.drawable\.msg_edit, getString\(R\.string\.LulChatTools\)\);/);
    assert.match(chat, /if \(id == 97104\) \{ showLunagramChatTools\(\); return; \}/);
    const tools = between(chat, 'private void showLunagramChatTools()', 'private void showLunagramWorkspace(int messageId)');
    assert.match(tools, /if \(!lunagramToolsContext\(\)\) return;/);
    assert.match(tools, /if \(!lunagramToolsContext\(\) \|\| !session\.isCurrent\(\)\) return;/);
    const guard = between(chat, 'private boolean lunagramToolsContext()', 'private CharSequence lunagramMessageText(');
    for (const condition of ['!isFinished', 'getParentActivity() != null', 'currentAccount == UserConfig.selectedAccount', '!SharedConfig.appLocked', '!SharedConfig.isWaitingForPasscodeEnter', 'currentEncryptedChat == null', '!isPeerNoForwards()', '!org.telegram.messenger.BlackHoleVault.blocks(currentAccount, dialog_id)']) {
        assert.ok(guard.includes(condition), `Missing tools restriction: ${condition}`);
    }
});

test('regression checks reject profile/tools routing, profile enabled gates and stale labels', () => {
    const route = between(chat, '} else if (id == liquid_header_avatar) {', '} else if (id == view_as_topics) {');
    for (const replacement of ['avatarContainer.performExternalAvatarClick();', 'showLunagramChatTools();']) {
        const changed = replaceChecked(route, 'showLiquidHeaderChatMenu();', replacement);
        assert.throws(() => assertNativeMenuRouting(replaceChecked(chat, route, changed)));
    }
    for (const replacement of ['true', 'avatarContainer.isAvatarClickable()']) {
        assert.throws(() => assertNativeMenuButton(replaceChecked(chat,
            'liquidHeaderAvatarItem.setEnabled(headerItem != null && headerItem.hasSubMenu());',
            `liquidHeaderAvatarItem.setEnabled(${replacement});`)));
    }
    for (const replacement of ['LulChatTools', 'AccDescrProfilePicture']) {
        assert.throws(() => assertNativeMenuButton(replaceChecked(chat,
            'liquidHeaderAvatarItem.setContentDescription(getString(R.string.AccDescrMoreOptions));',
            `liquidHeaderAvatarItem.setContentDescription(getString(R.string.${replacement}));`)));
    }
});

test('regression checks reject hidden anchors and removal of every native menu guard', () => {
    const helper = nativeMenuHelper(chat);
    for (const replacement of ['headerItem', 'null']) {
        const changed = replaceChecked(helper,
            'headerItem.toggleSubMenu(null, liquidHeaderAvatarItem);',
            `headerItem.toggleSubMenu(null, ${replacement});`);
        assert.throws(() => assertNativeMenuHelper(replaceChecked(chat, helper, changed)));
    }
    for (const condition of [...nativeMenuGuards, '!actionBar.getActionBarMenuOnItemClick().canOpenMenu()']) {
        const changed = replaceChecked(helper, condition, 'false');
        assert.throws(() => assertNativeMenuHelper(replaceChecked(chat, helper, changed)),
            `Guard regression should be rejected: ${condition}`);
    }
});
