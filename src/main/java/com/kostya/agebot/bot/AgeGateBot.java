package com.kostya.agebot.bot;

import com.kostya.agebot.config.BotConfig;
import com.kostya.agebot.db.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.CreateChatInviteLink;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.ChatInviteLink;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgeGateBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(AgeGateBot.class);
    private static final Pattern START_PATTERN = Pattern.compile("^/start(?:@[\\w_]+)?(?:\\s+(.+))?$");
    private static final Pattern ADMIN_PATTERN = Pattern.compile("^/admin(?:@[\\w_]+)?(?:\\s+.*)?$");
    private static final Pattern ADMIN_REMOVE_PATTERN = Pattern.compile("^admin:remove:(\\d+)$");
    private static final DateTimeFormatter REF_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final String CALLBACK_AGE_CONFIRM = "age:confirm";
    private static final String CALLBACK_ADMIN_MENU = "admin:menu";
    private static final String CALLBACK_ADMIN_STATS = "admin:stats";
    private static final String CALLBACK_ADMIN_REF_MENU = "admin:ref-menu";
    private static final String CALLBACK_ADMIN_REF_AUTO = "admin:ref-auto";
    private static final String CALLBACK_ADMIN_REF_LANDING = "admin:ref-landing";
    private static final String CALLBACK_ADMIN_REF_ADS = "admin:ref-ads";
    private static final String CALLBACK_ADMIN_REF_MANUAL = "admin:ref-manual";
    private static final String CALLBACK_ADMIN_SET_GROUP = "admin:set-group";
    private static final String CALLBACK_ADMIN_ADMINS = "admin:admins";
    private static final String CALLBACK_ADMIN_LIST_ALL = "admin:list-all";
    private static final String CALLBACK_ADMIN_ADD = "admin:add";
    private static final String CALLBACK_ADMIN_REMOVE_MENU = "admin:remove-menu";

    private final BotConfig config;
    private final Repository repository;
    private final Map<Long, PendingAction> pendingActions = new ConcurrentHashMap<>();

    public AgeGateBot(BotConfig config, Repository repository) {
        super(config.botToken());
        this.config = config;
        this.repository = repository;
    }

    @Override
    public String getBotUsername() {
        return config.botUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update == null) {
                return;
            }
            if (update.hasMessage() && update.getMessage().hasText()) {
                if (!isPrivateChat(update.getMessage())) {
                    return;
                }
                handleMessage(update.getMessage());
                return;
            }
            if (update.hasCallbackQuery()) {
                CallbackQuery callbackQuery = update.getCallbackQuery();
                if (callbackQuery.getMessage() != null && !isPrivateChat(callbackQuery.getMessage())) {
                    return;
                }
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception e) {
            log.error("Unhandled bot error", e);
        }
    }

    private void handleMessage(Message message) {
        if (message == null || message.getFrom() == null || !message.hasText()) {
            return;
        }
        repository.upsertUserProfile(message.getFrom());

        String text = message.getText().trim();
        long chatId = message.getChatId();
        long userId = message.getFrom().getId();

        if (isStartCommand(text)) {
            handleStart(message, text);
            return;
        }

        if (isAdminCommand(text)) {
            if (!repository.isAdmin(userId)) {
                sendText(chatId, "⛔ У вас нет доступа к админ-панели.", null);
                return;
            }
            pendingActions.remove(userId);
            sendAdminPanel(chatId);
            return;
        }

        PendingAction pendingAction = pendingActions.get(userId);
        if (pendingAction != null && !text.startsWith("/")) {
            handlePendingInput(message, pendingAction);
            return;
        }

        if (repository.isAdmin(userId)) {
            sendText(chatId, "ℹ️ Для управления используйте команду /admin", null);
        } else {
            sendText(chatId, "ℹ️ Используйте команду /start", null);
        }
    }

    private void handleStart(Message message, String text) {
        User user = message.getFrom();
        long chatId = message.getChatId();
        String payload = extractStartPayload(text);
        String source = normalizeSource(payload);

        repository.recordStart(user, source, payload);

        if (repository.isUserVerified(user.getId())) {
            sendText(chatId, "Вы уже активны.\nНовая ссылка не требуется.", null);
            return;
        }

        String welcome = "Добро пожаловать. \nДля получения доступа к закрытому чату подтвердите, пожалуйста, ваш возраст";
        sendText(chatId, welcome, ageGateKeyboard());
    }

    private void handleCallback(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.getData() == null || callbackQuery.getFrom() == null) {
            return;
        }
        if (callbackQuery.getMessage() == null) {
            return;
        }

        String data = callbackQuery.getData();
        repository.upsertUserProfile(callbackQuery.getFrom());
        long chatId = callbackQuery.getMessage().getChatId();
        long userId = callbackQuery.getFrom().getId();

        if (CALLBACK_AGE_CONFIRM.equals(data)) {
            answerCallback(callbackQuery.getId(), "");
            handleAgeConfirm(chatId, userId);
            return;
        }

        if (!data.startsWith("admin:")) {
            answerCallback(callbackQuery.getId(), "");
            return;
        }

        if (!repository.isAdmin(userId)) {
            answerCallback(callbackQuery.getId(), "⛔ Нет доступа");
            return;
        }

        answerCallback(callbackQuery.getId(), "");
        switch (data) {
            case CALLBACK_ADMIN_MENU -> {
                pendingActions.remove(userId);
                sendAdminPanel(chatId);
            }
            case CALLBACK_ADMIN_STATS -> sendStats(chatId);
            case CALLBACK_ADMIN_REF_MENU -> sendRefMenu(chatId);
            case CALLBACK_ADMIN_REF_AUTO -> generateAndSendRefLink(chatId, userId, autoSource());
            case CALLBACK_ADMIN_REF_LANDING -> generateAndSendRefLink(chatId, userId, "landing");
            case CALLBACK_ADMIN_REF_ADS -> generateAndSendRefLink(chatId, userId, "ads");
            case CALLBACK_ADMIN_REF_MANUAL -> {
                pendingActions.put(userId, PendingAction.CUSTOM_SOURCE);
                sendText(chatId, "✍️ Отправьте source (например: landing, ads, partner1).", backToMenuKeyboard());
            }
            case CALLBACK_ADMIN_SET_GROUP -> {
                pendingActions.put(userId, PendingAction.SET_GROUP_ID);
                sendText(chatId, "🎯 Отправьте новый groupId (например: -1001234567890).", backToMenuKeyboard());
            }
            case CALLBACK_ADMIN_ADMINS -> sendAdminsMenu(chatId);
            case CALLBACK_ADMIN_LIST_ALL -> sendAllAdmins(chatId);
            case CALLBACK_ADMIN_ADD -> {
                pendingActions.put(userId, PendingAction.ADD_ADMIN);
                sendText(chatId, "👤 Отправьте user_id нового админа.", backToMenuKeyboard());
            }
            case CALLBACK_ADMIN_REMOVE_MENU -> sendRemoveAdminsMenu(chatId, userId);
            default -> {
                Matcher removeMatcher = ADMIN_REMOVE_PATTERN.matcher(data);
                if (removeMatcher.matches()) {
                    long targetId = Long.parseLong(removeMatcher.group(1));
                    removeAdmin(chatId, userId, targetId);
                } else {
                    sendText(chatId, "⚠️ Неизвестная команда панели.", adminPanelKeyboard());
                }
            }
        }
    }

    private void handleAgeConfirm(long chatId, long userId) {
        if (repository.isUserVerified(userId)) {
            sendText(chatId, "Вы уже активны.\nНовая ссылка не требуется.", null);
            return;
        }

        String groupId = repository.getGroupId().orElse(config.defaultGroupId());
        if (groupId == null || groupId.isBlank()) {
            sendText(chatId, "⚠️ Ссылка пока недоступна. Администратор не настроил groupId.", null);
            return;
        }

        try {
            CreateChatInviteLink createInviteLink = new CreateChatInviteLink();
            createInviteLink.setChatId(groupId);
            createInviteLink.setName("user_" + userId + "_" + System.currentTimeMillis());
            createInviteLink.setMemberLimit(1);

            ChatInviteLink inviteLink = execute(createInviteLink);
            boolean updated = repository.markUserVerified(userId);
            if (!updated) {
                sendText(chatId, "Вы уже активны.\nНовая ссылка не требуется.", null);
                return;
            }

            repository.saveInviteLink(userId, groupId, inviteLink.getInviteLink());
            sendText(chatId, "Вы добавлены.\nПриятного общения.\n\n" + inviteLink.getInviteLink(), null);
        } catch (TelegramApiException e) {
            log.error("Failed to generate invite link for user {}", userId, e);
            sendText(chatId, "⚠️ Не удалось выдать ссылку. Проверьте права бота в целевой группе.", null);
        }
    }

    private void handlePendingInput(Message message, PendingAction pendingAction) {
        long userId = message.getFrom().getId();
        long chatId = message.getChatId();
        String text = message.getText().trim();

        if (!repository.isAdmin(userId)) {
            pendingActions.remove(userId);
            sendText(chatId, "⛔ Доступ в админ-панель закрыт.", null);
            return;
        }

        switch (pendingAction) {
            case ADD_ADMIN -> {
                Long adminId = parsePositiveLong(text);
                if (adminId == null) {
                    sendText(chatId, "⚠️ Неверный user_id. Пришлите число.", backToMenuKeyboard());
                    return;
                }
                pendingActions.remove(userId);
                boolean added = repository.addAdmin(adminId, userId);
                if (added) {
                    sendText(chatId, "✅ Админ добавлен: " + adminId, adminPanelKeyboard());
                } else {
                    sendText(chatId, "ℹ️ Этот пользователь уже админ.", adminPanelKeyboard());
                }
            }
            case SET_GROUP_ID -> {
                String groupId = text;
                if (!isValidGroupId(groupId)) {
                    sendText(chatId, "⚠️ Неверный groupId. Пример: -1001234567890", backToMenuKeyboard());
                    return;
                }
                pendingActions.remove(userId);
                repository.setGroupId(groupId);
                sendText(chatId, "✅ groupId обновлен: " + groupId, adminPanelKeyboard());
            }
            case CUSTOM_SOURCE -> {
                String source = sanitizeSource(text);
                if (source == null) {
                    sendText(chatId, "⚠️ Source пустой или некорректный. Попробуйте еще раз.", backToMenuKeyboard());
                    return;
                }
                pendingActions.remove(userId);
                generateAndSendRefLink(chatId, userId, source);
            }
        }
    }

    private void removeAdmin(long chatId, long requesterId, long targetId) {
        if (targetId == requesterId) {
            sendText(chatId, "⚠️ Нельзя удалить самого себя из админов.", adminPanelKeyboard());
            return;
        }
        if (repository.getAdminCount() <= 1) {
            sendText(chatId, "⚠️ Должен остаться хотя бы один админ.", adminPanelKeyboard());
            return;
        }

        boolean removed = repository.removeAdmin(targetId);
        if (removed) {
            sendText(chatId, "✅ Админ удален: " + targetId, adminPanelKeyboard());
        } else {
            sendText(chatId, "ℹ️ Админ не найден.", adminPanelKeyboard());
        }
    }

    private void sendAdminPanel(long chatId) {
        Optional<String> groupId = repository.getGroupId();
        String groupLine = groupId.map(s -> "Текущий groupId: " + s).orElse("Текущий groupId: не задан");
        String text = "🛠 Админ-панель\n" + groupLine;
        sendText(chatId, text, adminPanelKeyboard());
    }

    private void sendStats(long chatId) {
        Repository.Stats stats = repository.getStats();
        StringBuilder builder = new StringBuilder();
        builder.append("📊 Статистика\n");
        builder.append("Переходы (/start): ").append(stats.totalStarts()).append("\n");
        builder.append("Уникальные переходы: ").append(stats.uniqueStarts()).append("\n");
        builder.append("Пользователи в базе: ").append(stats.totalUsers()).append("\n");
        builder.append("Подтвердили возраст: ").append(stats.totalVerified()).append("\n");

        if (stats.totalUsers() > 0) {
            double conversion = (stats.totalVerified() * 100.0) / stats.totalUsers();
            builder.append("Конверсия подтверждения: ").append(String.format(Locale.US, "%.2f", conversion)).append("%\n");
        }

        builder.append("\nИсточники:\n");
        if (stats.sourceStats().isEmpty()) {
            builder.append("Нет данных");
        } else {
            int limit = Math.min(stats.sourceStats().size(), 20);
            for (int i = 0; i < limit; i++) {
                Repository.SourceStat sourceStat = stats.sourceStats().get(i);
                builder.append(i + 1)
                        .append(". ")
                        .append(sourceStat.source())
                        .append(" | start=")
                        .append(sourceStat.starts())
                        .append(", users=")
                        .append(sourceStat.users())
                        .append(", 18+=")
                        .append(sourceStat.verifiedUsers())
                        .append("\n");
            }
        }

        sendText(chatId, builder.toString(), backToMenuKeyboard());
    }

    private void sendRefMenu(long chatId) {
        sendText(chatId, "🔗 Генерация ссылок на бота", refMenuKeyboard());
    }

    private void sendAdminsMenu(long chatId) {
        sendText(chatId, "👥 Управление администраторами", adminsMenuKeyboard());
    }

    private void sendAllAdmins(long chatId) {
        List<Repository.AdminProfile> profiles = repository.getAdminProfiles();
        if (profiles.isEmpty()) {
            sendText(chatId, "ℹ️ Список админов пуст.", backToMenuKeyboard());
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("📋 Все админы\n");
        for (int i = 0; i < profiles.size(); i++) {
            Repository.AdminProfile profile = profiles.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(formatAdminName(profile))
                    .append(" | ")
                    .append(formatAdminTag(profile))
                    .append(" | id=")
                    .append(profile.userId())
                    .append("\n");
        }
        sendText(chatId, builder.toString(), backToAdminsKeyboard());
    }

    private void sendRemoveAdminsMenu(long chatId, long requesterId) {
        List<Long> admins = repository.getAdminIds();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Long adminId : admins) {
            if (adminId == null || adminId == requesterId) {
                continue;
            }
            rows.add(List.of(InlineKeyboardButton.builder()
                    .text("❌ Удалить " + adminId)
                    .callbackData("admin:remove:" + adminId)
                    .build()));
        }
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("🔙 Назад")
                .callbackData(CALLBACK_ADMIN_ADMINS)
                .build()));

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(rows);
        if (rows.size() == 1) {
            sendText(chatId, "ℹ️ Нет админов для удаления.", keyboard);
        } else {
            sendText(chatId, "➖ Выберите админа для удаления", keyboard);
        }
    }

    private void generateAndSendRefLink(long chatId, long adminId, String source) {
        repository.saveGeneratedSource(source, adminId);
        String link = "https://t.me/" + config.botUsername() + "?start=" +
                URLEncoder.encode("ref=" + source, StandardCharsets.UTF_8);

        String text = "🔗 Новая ссылка:\n" + link + "\n\nsource: " + source;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⚡ Еще авто source")
                        .callbackData(CALLBACK_ADMIN_REF_AUTO)
                        .build(),
                InlineKeyboardButton.builder()
                        .text("🔙 В меню")
                        .callbackData(CALLBACK_ADMIN_MENU)
                        .build()
        ));
        sendText(chatId, text, new InlineKeyboardMarkup(rows));
    }

    private InlineKeyboardMarkup ageGateKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("✅ Мне есть 18")
                .callbackData(CALLBACK_AGE_CONFIRM)
                .build()));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup adminPanelKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                InlineKeyboardButton.builder().text("📊 Статистика").callbackData(CALLBACK_ADMIN_STATS).build(),
                InlineKeyboardButton.builder().text("🔗 Ссылки").callbackData(CALLBACK_ADMIN_REF_MENU).build()
        ));
        rows.add(List.of(
                InlineKeyboardButton.builder().text("🎯 Изменить groupId").callbackData(CALLBACK_ADMIN_SET_GROUP).build(),
                InlineKeyboardButton.builder().text("👥 Админы").callbackData(CALLBACK_ADMIN_ADMINS).build()
        ));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup refMenuKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                InlineKeyboardButton.builder()
                        .text("🌐 Для сайта")
                        .callbackData(CALLBACK_ADMIN_REF_LANDING)
                        .build(),
                InlineKeyboardButton.builder()
                        .text("📢 Для рекламы")
                        .callbackData(CALLBACK_ADMIN_REF_ADS)
                        .build()
        ));
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("⚡ Авто source")
                .callbackData(CALLBACK_ADMIN_REF_AUTO)
                .build()));
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("✍️ Ввести source")
                .callbackData(CALLBACK_ADMIN_REF_MANUAL)
                .build()));
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("🔙 Назад")
                .callbackData(CALLBACK_ADMIN_MENU)
                .build()));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup adminsMenuKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("📋 Все админы")
                .callbackData(CALLBACK_ADMIN_LIST_ALL)
                .build()));
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("➕ Добавить админа")
                .callbackData(CALLBACK_ADMIN_ADD)
                .build()));
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("➖ Удалить админа")
                .callbackData(CALLBACK_ADMIN_REMOVE_MENU)
                .build()));
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("🔙 Назад")
                .callbackData(CALLBACK_ADMIN_MENU)
                .build()));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup backToAdminsKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("🔙 В админы")
                .callbackData(CALLBACK_ADMIN_ADMINS)
                .build()));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup backToMenuKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(InlineKeyboardButton.builder()
                .text("🔙 В меню")
                .callbackData(CALLBACK_ADMIN_MENU)
                .build()));
        return new InlineKeyboardMarkup(rows);
    }

    private void sendText(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(String.valueOf(chatId));
        sendMessage.setText(text);
        if (keyboard != null) {
            sendMessage.setReplyMarkup(keyboard);
        }
        sendMessage.disableWebPagePreview();

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}", chatId, e);
        }
    }

    private void answerCallback(String callbackQueryId, String text) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        answer.setText(text);
        answer.setShowAlert(false);
        try {
            execute(answer);
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback", e);
        }
    }

    private boolean isStartCommand(String text) {
        return text != null && START_PATTERN.matcher(text).matches();
    }

    private boolean isAdminCommand(String text) {
        return text != null && ADMIN_PATTERN.matcher(text).matches();
    }

    private String extractStartPayload(String text) {
        Matcher matcher = START_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String payload = matcher.group(1);
        if (payload == null || payload.isBlank()) {
            return null;
        }
        return payload.trim();
    }

    private String normalizeSource(String payload) {
        if (payload == null || payload.isBlank()) {
            return "direct";
        }

        String value = payload.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("ref=")) {
            value = value.substring(4);
        }

        try {
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
        }

        String source = sanitizeSource(value);
        if (source == null) {
            return "direct";
        }
        return source;
    }

    private String sanitizeSource(String raw) {
        if (raw == null) {
            return null;
        }
        String source = raw.trim().toLowerCase(Locale.ROOT);
        source = source.replaceAll("\\s+", "_");
        source = source.replaceAll("[^a-z0-9_-]", "_");
        source = source.replaceAll("_+", "_");
        source = source.replaceAll("^_+", "");
        source = source.replaceAll("_+$", "");
        if (source.isBlank()) {
            return null;
        }
        if (source.length() > 64) {
            source = source.substring(0, 64);
        }
        return source;
    }

    private String autoSource() {
        return "ref_" + LocalDateTime.now().format(REF_TIME);
    }

    private Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isValidGroupId(String groupId) {
        if (groupId == null) {
            return false;
        }
        return groupId.trim().matches("^-?\\d+$");
    }

    private boolean isPrivateChat(MaybeInaccessibleMessage message) {
        if (message == null) {
            return false;
        }
        return message.isUserMessage();
    }

    private String formatAdminName(Repository.AdminProfile profile) {
        String first = profile.firstName() == null ? "" : profile.firstName().trim();
        String last = profile.lastName() == null ? "" : profile.lastName().trim();
        String full = (first + " " + last).trim();
        if (full.isBlank()) {
            return "Без имени";
        }
        return full;
    }

    private String formatAdminTag(Repository.AdminProfile profile) {
        String username = profile.username();
        if (username == null || username.isBlank()) {
            return "@без_тега";
        }
        return "@" + username.trim();
    }

    private enum PendingAction {
        ADD_ADMIN,
        SET_GROUP_ID,
        CUSTOM_SOURCE
    }
}
