package com.gerhart.bot.bot;

import com.gerhart.bot.config.AppConfig;
import com.gerhart.bot.model.Sale;
import com.gerhart.bot.model.SaleStatus;
import com.gerhart.bot.model.User;
import com.gerhart.bot.service.BotService;
import com.gerhart.bot.state.StateStore;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AutoGoodsBot extends TelegramLongPollingBot {
    private static final String STATE_AWAIT_EMAIL = "AWAIT_EMAIL";
    private static final String STATE_AWAIT_PAYMENT = "AWAIT_PAYMENT";
    private static final String STATE_AWAIT_PROOF = "AWAIT_PROOF";
    private static final String STATE_AWAIT_TEXT_EDIT = "AWAIT_TEXT_EDIT";

    private static final String TEXT_START = "start";
    private static final String TEXT_ABOUT = "about";
    private static final String TEXT_SUPPORT = "support";
    private static final String TEXT_REF_REQUIRED = "ref_required";
    private static final String TEXT_MENTOR_CONTACTS_HINT = "mentor_contacts_hint";
    private static final String TEXT_BTN_TEAM = "btn_team";
    private static final String TEXT_BTN_INVITE = "btn_invite";
    private static final String TEXT_BTN_PROGRESS = "btn_progress";
    private static final String TEXT_BTN_BUY = "btn_buy";
    private static final String TEXT_BTN_MENTOR_CONTACTS = "btn_mentor_contacts";
    private static final String TEXT_BTN_MENTOR_UNREACHABLE = "btn_mentor_unreachable";
    private static final String TEXT_BTN_PENDING = "btn_pending";
    private static final String TEXT_BTN_ABOUT = "btn_about";
    private static final String TEXT_BTN_SUPPORT = "btn_support";
    private static final String TEXT_BTN_ADMIN = "btn_admin";

    private static final String DEFAULT_START_TEXT = """
            🚗 Добро пожаловать в клуб автотоваров!

            Здесь вы сможете:
            • развивать свою команду;
            • отслеживать прогресс по уровням;
            • покупать новый уровень и отправлять чек;
            • получать поддержку и понятную навигацию по проекту.

            Выберите нужный раздел ниже 👇
            """;
    private static final String DEFAULT_ABOUT_TEXT = """
            ℹ️ О проекте

            Это система командных продаж автотоваров с уровнями.
            Сначала активируется 1-й уровень через подтвержденную оплату,
            затем открываются приглашения и рост по следующим уровням.

            Скоро здесь будет опубликовано подробное описание правил проекта.
            """;
    private static final int ABOUT_PAGE_SIZE = 3500;
    private static final Path START_MENU_PHOTO_PATH = Paths.get("media", "1.jpeg");
    private static final String START_MENU_PHOTO_FILE_ID_KEY = "__start_menu_photo_file_id";

    private final AppConfig config;
    private final BotService service;
    private final StateStore stateStore;
    private volatile String cachedStartMenuPhotoFileId;

    public AutoGoodsBot(AppConfig config, BotService service, StateStore stateStore) {
        super(config.botToken());
        this.config = config;
        this.service = service;
        this.stateStore = stateStore;
        this.cachedStartMenuPhotoFileId = emptyToNull(service.getText(START_MENU_PHOTO_FILE_ID_KEY, ""));
    }

    @Override
    public String getBotUsername() {
        return config.botUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(Message message) throws TelegramApiException {
        if (message.getFrom() == null) {
            return;
        }

        String text = message.hasText() ? message.getText().trim() : null;
        String payload = extractStartPayload(text);
        User user = service.ensureUser(
                message.getFrom().getId(),
                message.getFrom().getUserName(),
                message.getFrom().getFirstName(),
                payload
        );
        user = service.refreshUser(user);

        if (isUnauthorizedWithoutRef(user)) {
            sendText(user.tgId(), service.getText(TEXT_REF_REQUIRED, defaultRefRequiredText()), unauthKeyboard());
            return;
        }

        if (text != null && text.startsWith("/start")) {
            sendMainMenu(user, service.getText(TEXT_START, DEFAULT_START_TEXT));
            return;
        }

        if (text != null && text.equalsIgnoreCase("/admin")) {
            sendAdminPanel(user);
            return;
        }

        Optional<StateStore.State> stateOpt = stateStore.getState(user.tgId());
        if (stateOpt.isPresent()) {
            handleStateInput(user, message, stateOpt.get());
            return;
        }

        if (text != null && text.startsWith("/menu")) {
            sendMainMenu(user, "🏠 Главное меню:");
            return;
        }

        sendMainMenu(user, "✨ Используйте кнопки ниже для работы в системе:");
    }

    private void handleStateInput(User user, Message message, StateStore.State state) throws TelegramApiException {
        switch (state.state()) {
            case STATE_AWAIT_EMAIL -> {
                if (!message.hasText()) {
                    sendText(user.tgId(), "📧 Отправьте e-mail текстом.", backMenuKeyboard());
                    return;
                }
                String email = message.getText().trim();
                service.setEmail(user, email);

                if (state.payload() != null && state.payload().startsWith("proof|")) {
                    String[] parts = state.payload().split("\\|", 4);
                    if (parts.length == 4) {
                        try {
                            int level = Integer.parseInt(parts[1]);
                            String proofType = parts[2];
                            String proofFileId = parts[3];
                            Sale sale = service.createPendingSale(user, level, proofType, proofFileId);
                            stateStore.clearState(user.tgId());
                            User seller = service.getUserById(sale.sellerUserId());
                            sendText(
                                    user.tgId(),
                                    "✅ E-mail сохранен, чек отправлен на проверку. После подтверждения уровень будет активирован.",
                                    mainMenuKeyboard(service.refreshUser(user))
                            );
                            notifyReviewers(sale, seller, user);
                            return;
                        } catch (Exception ignored) {
                        }
                    }
                }

                stateStore.clearState(user.tgId());
                sendMainMenu(service.refreshUser(user), "✅ E-mail сохранен.");
            }
            case STATE_AWAIT_PAYMENT -> {
                if (!message.hasText()) {
                    sendText(user.tgId(), "💳 Отправьте платежные реквизиты текстом.", backMenuKeyboard());
                    return;
                }
                service.setPaymentDetails(user, message.getText().trim());
                stateStore.clearState(user.tgId());
                sendMainMenu(service.refreshUser(user), "✅ Платежные реквизиты сохранены.");
            }
            case STATE_AWAIT_PROOF -> {
                if (!message.hasPhoto() && !message.hasDocument()) {
                    sendText(user.tgId(), "🧾 Отправьте чек файлом или фото.", backMenuKeyboard());
                    return;
                }

                int level;
                try {
                    level = Integer.parseInt(state.payload());
                } catch (Exception e) {
                    stateStore.clearState(user.tgId());
                    sendMainMenu(user, "⚠️ Ошибка состояния. Начните покупку уровня заново.");
                    return;
                }

                int next = service.getNextLevel(user);
                if (level != next) {
                    stateStore.clearState(user.tgId());
                    sendMainMenu(user, "⚠️ Чек не соответствует следующему уровню. Запустите покупку заново.");
                    return;
                }

                Optional<String> validationError = service.validateCanBuyNextLevel(user);
                if (validationError.isPresent()) {
                    stateStore.clearState(user.tgId());
                    sendMainMenu(user, validationError.get());
                    return;
                }

                String proofType;
                String proofFileId;
                if (message.hasPhoto()) {
                    PhotoSize best = message.getPhoto().stream()
                            .max(Comparator.comparing(PhotoSize::getFileSize))
                            .orElse(message.getPhoto().get(0));
                    proofType = "PHOTO";
                    proofFileId = best.getFileId();
                } else {
                    Document document = message.getDocument();
                    proofType = "DOCUMENT";
                    proofFileId = document.getFileId();
                }

                if (requiresEmail(user)) {
                    stateStore.setState(user.tgId(), STATE_AWAIT_EMAIL, "proof|" + level + "|" + proofType + "|" + proofFileId);
                    sendText(
                            user.tgId(),
                            "📧 Перед отправкой чека нужно указать актуальный e-mail.\n"
                                    + "Отправьте e-mail одним сообщением, после этого чек уйдет на проверку.",
                            emailRequiredKeyboard()
                    );
                    return;
                }

                Sale sale = service.createPendingSale(user, level, proofType, proofFileId);
                stateStore.clearState(user.tgId());

                User seller = service.getUserById(sale.sellerUserId());
                sendText(
                        user.tgId(),
                        "✅ Чек отправлен на проверку. После подтверждения уровень будет активирован.",
                        mainMenuKeyboard(service.refreshUser(user))
                );
                notifyReviewers(sale, seller, user);
            }
            case STATE_AWAIT_TEXT_EDIT -> {
                if (!service.isAdmin(user)) {
                    stateStore.clearState(user.tgId());
                    sendText(user.tgId(), "⛔ Нет доступа.", backMenuKeyboard());
                    return;
                }
                if (!message.hasText()) {
                    sendText(user.tgId(), "✍️ Отправьте текстом следующую часть или нажмите «Готово».", textEditKeyboard());
                    return;
                }
                TextEditPayload payload = decodeTextEditPayload(state.payload());
                if (!isEditableTextKey(payload.key())) {
                    stateStore.clearState(user.tgId());
                    sendText(user.tgId(), "⚠️ Не удалось определить раздел текста.", backMenuKeyboard());
                    return;
                }

                String part = message.getText().trim();
                if (part.isBlank()) {
                    sendText(user.tgId(), "⚠️ Пустая часть не добавлена. Пришлите текст или нажмите «Готово».", textEditKeyboard());
                    return;
                }

                List<String> parts = new ArrayList<>(payload.parts());
                parts.add(part);
                stateStore.setState(
                        user.tgId(),
                        STATE_AWAIT_TEXT_EDIT,
                        encodeTextEditPayload(payload.key(), parts)
                );
                sendText(
                        user.tgId(),
                        "✅ Текст добавлен.\n\nПришлите следующую часть или нажмите «Готово».",
                        textEditKeyboard()
                );
            }
            default -> {
                stateStore.clearState(user.tgId());
                sendMainMenu(user, "ℹ️ Состояние очищено. Продолжайте через меню.");
            }
        }
    }

    private void notifyReviewers(Sale sale, User seller, User buyer) throws TelegramApiException {
        String reviewText = buildSaleReviewText(sale, seller, buyer);
        InlineKeyboardMarkup kb = saleReviewKeyboard(sale.id());

        sendProofWithCaption(seller.tgId(), sale, reviewText, kb);

        for (Long adminTgId : config.adminTgIds()) {
            if (adminTgId == seller.tgId()) {
                continue;
            }
            sendProofWithCaption(adminTgId, sale, "[ADMIN] " + reviewText, kb);
        }
    }

    private void handleCallback(CallbackQuery callbackQuery) throws TelegramApiException {
        if (callbackQuery.getFrom() == null) {
            return;
        }

        String data = callbackQuery.getData();
        User user = service.ensureUser(
                callbackQuery.getFrom().getId(),
                callbackQuery.getFrom().getUserName(),
                callbackQuery.getFrom().getFirstName(),
                null
        );
        user = service.refreshUser(user);

        if (data == null) {
            answerCallback(callbackQuery.getId(), "⚠️ Пустая команда");
            return;
        }

        if (isUnauthorizedWithoutRef(user) && !"support".equals(data) && !"about".equals(data)) {
            sendText(user.tgId(), service.getText(TEXT_REF_REQUIRED, defaultRefRequiredText()), unauthKeyboard());
            answerCallback(callbackQuery.getId(), "⛔ Нужна реферальная ссылка");
            return;
        }

        switch (data) {
            case "menu" -> sendMainMenu(user, "🏠 Главное меню:");
            case "team" -> sendTeam(user);
            case "invite" -> sendInvite(user);
            case "progress" -> sendProgress(user);
            case "buy" -> sendBuyLevel(user);
            case "mentor_contacts" -> sendMentorContacts(user);
            case "mentor_unreachable" -> escalateMentor(user);
            case "support" -> sendSupport(user);
            case "set_email" -> {
                stateStore.setState(user.tgId(), STATE_AWAIT_EMAIL, null);
                sendText(user.tgId(), "📧 Отправьте e-mail одним сообщением.", backMenuKeyboard());
            }
            case "set_payment" -> {
                stateStore.setState(user.tgId(), STATE_AWAIT_PAYMENT, null);
                sendText(user.tgId(), "💳 Отправьте платежные реквизиты (банк, карта/счет, ФИО и т.д.).", backMenuKeyboard());
            }
            case "pending" -> sendPendingPayments(user);
            case "about" -> sendAbout(user);
            case "about_page" -> sendAbout(user, 0);
            case "admin" -> sendAdminPanel(user);
            case "admin_texts" -> sendAdminTexts(user);
            case "admin_users" -> sendAdminUsers(user);
            case "admin_stats" -> sendAdminStats(user);
            case "done_text_edit" -> finishTextEdit(user);
            case "cancel_text_edit" -> cancelTextEdit(user);
            default -> {
                if (data.startsWith("proof_start:")) {
                    startProofUpload(user, data);
                } else if (data.startsWith("edit_text:")) {
                    startEditText(user, data);
                } else if (data.startsWith("about_page:")) {
                    showAboutPage(user, data);
                } else if (data.startsWith("sale_ok:")) {
                    approveSale(user, data);
                } else if (data.startsWith("sale_no:")) {
                    rejectSale(user, data);
                } else {
                    sendText(user.tgId(), "⚠️ Неизвестная команда.", mainMenuKeyboard(user));
                }
            }
        }

        answerCallback(callbackQuery.getId(), "✅");
    }

    private void sendTeam(User user) throws TelegramApiException {
        List<User> team = service.getDirectReferrals(user, 20);
        int direct = service.countDirectReferrals(user);

        StringBuilder sb = new StringBuilder();
        sb.append("👥 Ваша команда\n");
        sb.append("Прямых участников: ").append(direct).append("\n\n");
        if (team.isEmpty()) {
            sb.append("Пока нет приглашенных пользователей.");
        } else {
            for (User member : team) {
                sb.append("- ").append(displayUser(member)).append(", уровень ").append(member.purchasedLevel()).append("\n");
            }
        }

        sendText(user.tgId(), sb.toString(), backMenuKeyboard());
    }

    private void sendInvite(User user) throws TelegramApiException {
        int direct = service.countDirectReferrals(user);
        int limit = service.getDirectReferralLimit(user);

        StringBuilder sb = new StringBuilder();
        sb.append("🔗 Приглашение участника\n\n");
        sb.append("Ваша реферальная ссылка:\n").append(service.getInviteLink(user)).append("\n\n");
        if (limit == 0) {
            sb.append("Сначала активируйте 1-й уровень через покупку товара у наставника.\n");
            sb.append("После подтверждения оплаты откроется приглашение участников.");
        } else if (limit == Integer.MAX_VALUE) {
            sb.append("Лимит приглашений на 1-й уровень: без ограничений.");
        } else {
            sb.append("Лимит приглашений на 1-й уровень: ").append(direct).append("/").append(limit).append(".");
            if (direct >= limit) {
                sb.append("\nБлок активирован. Для снятия ограничения нужно купить следующий уровень.");
            }
        }

        sendText(user.tgId(), sb.toString(), backMenuKeyboard());
    }

    private void sendProgress(User user) throws TelegramApiException {
        user = service.refreshUser(user);
        int next = service.getNextLevel(user);

        StringBuilder sb = new StringBuilder();
        sb.append("📈 Ваш прогресс\n\n");
        sb.append("Текущий открытый уровень: ").append(user.purchasedLevel()).append("\n");
        sb.append("Приглашено напрямую: ").append(service.countDirectReferrals(user)).append("\n");
        if (user.purchasedLevel() == 0) {
            sb.append("🔒 1-й уровень еще не активирован. Сначала оплатите товар 1-го уровня.\n");
        }

        if (next != -1) {
            int sold = service.countConfirmedSalesAtLevel(user, next);
            sb.append("Продаж на уровне ").append(next)
                    .append(": ").append(sold)
                    .append("/3 (до покупки уровня ").append(next + 1).append(")\n");
        } else {
            sb.append("Максимальный уровень уже открыт.\n");
        }

        sendText(user.tgId(), sb.toString(), backMenuKeyboard());
    }

    private void sendBuyLevel(User user) throws TelegramApiException {
        user = service.refreshUser(user);
        int next = service.getNextLevel(user);
        if (next == -1) {
            sendText(user.tgId(), "Вы уже купили максимальный уровень.", backMenuKeyboard());
            return;
        }

        Optional<String> err = service.validateCanBuyNextLevel(user);
        if (err.isPresent()) {
            sendText(user.tgId(), err.get(), backMenuKeyboard());
            return;
        }

        User mentor = service.findMentorForLevel(user, next).orElseThrow();

        StringBuilder sb = new StringBuilder();
        sb.append("🛒 Покупка уровня ").append(next).append("\n\n");
        sb.append("Наставник для оплаты: ").append(displayUser(mentor)).append("\n");
        sb.append("Telegram: ").append(telegramContact(mentor)).append("\n");
        sb.append("E-mail: ").append(nullToDash(mentor.email())).append("\n");
        sb.append("\nРеквизиты наставник отправит вам лично в чате после обращения.\n");
        sb.append("После оплаты загрузите чек кнопкой ниже 👇");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(button("🧾 Загрузить чек", "proof_start:" + next)),
                List.of(button("🚨 Наставник не выходит на связь", "mentor_unreachable")),
                List.of(button("⬅️ Назад", "menu"))
        ));

        sendText(user.tgId(), sb.toString(), kb);
    }

    private void sendMentorContacts(User user) throws TelegramApiException {
        Optional<User> mentorOpt = service.findDirectMentor(user);
        if (mentorOpt.isEmpty()) {
            sendText(user.tgId(), "У вас пока нет прямого наставника. Обратитесь в поддержку.", backMenuKeyboard());
            return;
        }

        User mentor = mentorOpt.get();
        String mentorHint = service.getText(
                TEXT_MENTOR_CONTACTS_HINT,
                "ℹ️ Актуальные реквизиты наставник отправит вам лично."
        );
        String text = "📇 Контакты вашего наставника\n\n"
                + "Имя: " + displayUser(mentor) + "\n"
                + "Telegram: " + telegramContact(mentor) + "\n"
                + "E-mail: " + nullToDash(mentor.email()) + "\n\n"
                + mentorHint;

        sendText(user.tgId(), text, backMenuKeyboard());
    }

    private void escalateMentor(User user) throws TelegramApiException {
        user = service.refreshUser(user);
        int nextLevel = service.getNextLevel(user);
        if (nextLevel == -1) {
            sendText(user.tgId(), "ℹ️ У вас уже максимальный уровень.", backMenuKeyboard());
            return;
        }

        Optional<BotService.EscalationResult> resultOpt = service.escalateMentor(user, nextLevel);
        if (resultOpt.isEmpty()) {
            sendText(user.tgId(), "⚠️ Не удалось найти наставника уровнем выше. Обратитесь в поддержку.", backMenuKeyboard());
            return;
        }

        BotService.EscalationResult result = resultOpt.get();
        User previous = result.previousMentor();
        User newMentor = result.newMentor();

        String text = "✅ Контакты наставника уровнем выше отправлены.\n\n"
                + "Текущий наставник: " + displayUser(previous) + "\n"
                + "Наставник уровнем выше: " + displayUser(newMentor) + "\n"
                + "Telegram: " + telegramContact(newMentor) + "\n"
                + "E-mail: " + nullToDash(newMentor.email()) + "\n\n"
                + "ℹ️ Закрепление наставника не изменено. Это разовый контакт для продолжения оплаты.";
        sendText(user.tgId(), text, backMenuKeyboard());

        String notifyCurrentText = "🚨 Участник " + displayUser(user) + " (tgId=" + user.tgId() + ") нажал кнопку "
                + "«Наставник не выходит на связь» по уровню " + nextLevel + ".\n"
                + "Ему выданы контакты наставника уровнем выше: " + displayUser(newMentor) + ".\n"
                + "Пожалуйста, свяжитесь с участником.";
        sendText(previous.tgId(), notifyCurrentText, backMenuKeyboard());

        String notifyHigherText = "ℹ️ Участнику " + displayUser(user) + " (tgId=" + user.tgId() + ") выданы ваши контакты "
                + "как наставника уровнем выше для уровня " + nextLevel + ".\n"
                + "Закрепление наставника не менялось. При необходимости помогите с оплатой.";
        sendText(newMentor.tgId(), notifyHigherText, backMenuKeyboard());
    }

    private void sendSupport(User user) throws TelegramApiException {
        String defaultText = "🛟 Поддержка: " + config.supportContact();
        sendText(user.tgId(), service.getText(TEXT_SUPPORT, defaultText), backMenuKeyboard());
    }

    private void sendAbout(User user) throws TelegramApiException {
        sendAbout(user, 0);
    }

    private void sendAbout(User user, int page) throws TelegramApiException {
        String fullText = service.getText(TEXT_ABOUT, DEFAULT_ABOUT_TEXT);
        List<String> parts = splitIntoPages(fullText, ABOUT_PAGE_SIZE);
        int pageIndex = Math.max(0, Math.min(page, parts.size() - 1));
        String text = parts.get(pageIndex);
        if (parts.size() > 1) {
            text = text + "\n\n— Часть " + (pageIndex + 1) + " из " + parts.size();
        }
        sendText(user.tgId(), text, aboutKeyboard(pageIndex, parts.size()));
    }

    private void showAboutPage(User user, String data) throws TelegramApiException {
        int page = parseIntId(data, "about_page:");
        if (page < 0) {
            sendText(user.tgId(), "⚠️ Не удалось открыть раздел.", backMenuKeyboard());
            return;
        }
        sendAbout(user, page);
    }

    private void sendAdminTexts(User user) throws TelegramApiException {
        if (!service.isAdmin(user)) {
            sendText(user.tgId(), "⛔ Нет доступа.", backMenuKeyboard());
            return;
        }
        sendText(user.tgId(), "✍️ Выберите раздел, текст которого нужно изменить:", adminTextsKeyboard());
    }

    private void startEditText(User user, String data) throws TelegramApiException {
        if (!service.isAdmin(user)) {
            sendText(user.tgId(), "⛔ Нет доступа.", backMenuKeyboard());
            return;
        }
        String key = data.substring("edit_text:".length());
        if (!isEditableTextKey(key)) {
            sendText(user.tgId(), "⚠️ Неизвестный раздел текста.", backMenuKeyboard());
            return;
        }
        stateStore.setState(user.tgId(), STATE_AWAIT_TEXT_EDIT, encodeTextEditPayload(key, List.of()));

        String current = switch (key) {
            case TEXT_START -> service.getText(TEXT_START, DEFAULT_START_TEXT);
            case TEXT_ABOUT -> service.getText(TEXT_ABOUT, DEFAULT_ABOUT_TEXT);
            case TEXT_SUPPORT -> service.getText(TEXT_SUPPORT, "🛟 Поддержка: " + config.supportContact());
            case TEXT_REF_REQUIRED -> service.getText(TEXT_REF_REQUIRED, defaultRefRequiredText());
            case TEXT_MENTOR_CONTACTS_HINT -> service.getText(
                    TEXT_MENTOR_CONTACTS_HINT,
                    "ℹ️ Актуальные реквизиты наставник отправит вам лично."
            );
            case TEXT_BTN_TEAM -> buttonText(TEXT_BTN_TEAM, "👥 Моя команда");
            case TEXT_BTN_INVITE -> buttonText(TEXT_BTN_INVITE, "🔗 Пригласить");
            case TEXT_BTN_PROGRESS -> buttonText(TEXT_BTN_PROGRESS, "📈 Мой прогресс");
            case TEXT_BTN_BUY -> buttonText(TEXT_BTN_BUY, "🛒 Купить уровень");
            case TEXT_BTN_MENTOR_CONTACTS -> buttonText(TEXT_BTN_MENTOR_CONTACTS, "📇 Контакты наставника");
            case TEXT_BTN_MENTOR_UNREACHABLE -> buttonText(TEXT_BTN_MENTOR_UNREACHABLE, "🚨 Наставник не выходит на связь");
            case TEXT_BTN_PENDING -> buttonText(TEXT_BTN_PENDING, "💸 Новые оплаты (проверка)");
            case TEXT_BTN_ABOUT -> buttonText(TEXT_BTN_ABOUT, "ℹ️ О проекте");
            case TEXT_BTN_SUPPORT -> buttonText(TEXT_BTN_SUPPORT, "🛟 Помощь");
            case TEXT_BTN_ADMIN -> buttonText(TEXT_BTN_ADMIN, "🛠 Админ-панель");
            default -> "";
        };
        sendText(
                user.tgId(),
                "Текущий текст (предпросмотр):\n\n" + previewText(current, 1200)
                        + "\n\n✍️ Режим редактирования включен.\n"
                        + "Пришлите первую часть нового текста.\n"
                        + "После этого можно присылать следующие части и нажать «Готово».",
                textEditKeyboard()
        );
    }

    private void finishTextEdit(User user) throws TelegramApiException {
        if (!service.isAdmin(user)) {
            sendText(user.tgId(), "⛔ Нет доступа.", backMenuKeyboard());
            return;
        }

        Optional<StateStore.State> stateOpt = stateStore.getState(user.tgId());
        if (stateOpt.isEmpty() || !STATE_AWAIT_TEXT_EDIT.equals(stateOpt.get().state())) {
            sendText(user.tgId(), "ℹ️ Режим редактирования не активен.", adminTextsKeyboard());
            return;
        }

        TextEditPayload payload = decodeTextEditPayload(stateOpt.get().payload());
        if (!isEditableTextKey(payload.key())) {
            stateStore.clearState(user.tgId());
            sendText(user.tgId(), "⚠️ Не удалось определить раздел текста.", adminTextsKeyboard());
            return;
        }
        if (payload.parts().isEmpty()) {
            sendText(
                    user.tgId(),
                    "ℹ️ Вы пока не прислали ни одной части.\nПришлите текст или нажмите «Отмена».",
                    textEditKeyboard()
            );
            return;
        }

        String fullText = String.join("\n\n", payload.parts()).trim();
        service.setText(payload.key(), fullText);
        stateStore.clearState(user.tgId());
        sendText(
                user.tgId(),
                "✅ Текст обновлен.\n"
                        + "Раздел: " + payload.key() + "\n"
                        + "Частей: " + payload.parts().size(),
                adminTextsKeyboard()
        );
    }

    private void cancelTextEdit(User user) throws TelegramApiException {
        if (!service.isAdmin(user)) {
            sendText(user.tgId(), "⛔ Нет доступа.", backMenuKeyboard());
            return;
        }
        stateStore.clearState(user.tgId());
        sendText(user.tgId(), "❌ Редактирование отменено.", adminTextsKeyboard());
    }

    private void sendPaymentProfile(User user) throws TelegramApiException {
        user = service.refreshUser(user);
        String text = "💼 Ваши платежные данные\n\n"
                + "E-mail: " + nullToDash(user.email()) + "\n"
                + "Реквизиты: " + nullToDash(user.paymentDetails()) + "\n\n"
                + "Заполните их, чтобы участники могли переводить оплату напрямую.";

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(button("✏️ Изменить e-mail", "set_email")),
                List.of(button("✏️ Изменить реквизиты", "set_payment")),
                List.of(button("⬅️ Назад", "menu"))
        ));

        sendText(user.tgId(), text, kb);
    }

    private void sendPendingPayments(User user) throws TelegramApiException {
        List<Sale> pending = service.listPendingForUser(user, 20);
        if (pending.isEmpty()) {
            sendText(user.tgId(), "💤 Новых оплат нет.", backMenuKeyboard());
            return;
        }

        sendText(user.tgId(), "💸 Найдено заявок: " + pending.size(), backMenuKeyboard());
        for (Sale sale : pending) {
            User seller = service.getUserById(sale.sellerUserId());
            User buyer = service.getUserById(sale.buyerUserId());
            String reviewText = buildSaleReviewText(sale, seller, buyer);
            sendProofWithCaption(user.tgId(), sale, reviewText, saleReviewKeyboard(sale.id()));
        }
    }

    private void sendAdminPanel(User user) throws TelegramApiException {
        if (!service.isAdmin(user)) {
            sendText(user.tgId(), "⛔ Нет доступа.", backMenuKeyboard());
            return;
        }

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(button("💸 Новые оплаты", "pending")),
                List.of(button("🧾 Список участников", "admin_users")),
                List.of(button("📊 Статистика", "admin_stats")),
                List.of(button("✍️ Редактировать тексты", "admin_texts")),
                List.of(button("⬅️ Назад", "menu"))
        ));

        sendText(user.tgId(), "🛠 Админ-панель", kb);
    }

    private void sendAdminUsers(User user) throws TelegramApiException {
        if (!service.isAdmin(user)) {
            sendText(user.tgId(), "⛔ Нет доступа.", backMenuKeyboard());
            return;
        }

        List<User> users = service.listRecentUsers(30);
        StringBuilder sb = new StringBuilder();
        sb.append("🧾 Последние участники\n\n");
        for (User u : users) {
            sb.append("- id=").append(u.id())
                    .append(", ").append(displayUser(u))
                    .append(", tg=").append(u.tgId())
                    .append(", уровень=").append(u.purchasedLevel())
                    .append("\n");
        }

        sendText(user.tgId(), sb.toString(), backMenuKeyboard());
    }

    private void sendAdminStats(User user) throws TelegramApiException {
        if (!service.isAdmin(user)) {
            sendText(user.tgId(), "⛔ Нет доступа.", backMenuKeyboard());
            return;
        }

        String text = "📊 Статистика\n\n"
                + "Всего участников: " + service.countAllUsers() + "\n"
                + "Всего заявок на оплаты: " + service.countAllSales() + "\n"
                + "Ожидают проверки: " + service.countPendingSales();

        sendText(user.tgId(), text, backMenuKeyboard());
    }

    private void startProofUpload(User user, String data) throws TelegramApiException {
        int level = parseIntId(data, "proof_start:");
        if (level <= 0) {
            sendText(user.tgId(), "⚠️ Некорректный уровень.", backMenuKeyboard());
            return;
        }

        Optional<String> err = service.validateCanBuyNextLevel(user);
        if (err.isPresent()) {
            sendText(user.tgId(), err.get(), backMenuKeyboard());
            return;
        }

        int next = service.getNextLevel(user);
        if (level != next) {
            sendText(user.tgId(), "⚠️ Можно загрузить чек только для следующего уровня: " + next, backMenuKeyboard());
            return;
        }

        stateStore.setState(user.tgId(), STATE_AWAIT_PROOF, String.valueOf(level));
        sendText(user.tgId(), "🧾 Отправьте фото или файл чека одним сообщением.", backMenuKeyboard());
    }

    private void approveSale(User user, String data) throws TelegramApiException {
        long saleId = parseLongId(data, "sale_ok:");
        if (saleId <= 0) {
            sendText(user.tgId(), "⚠️ Некорректный id заявки.", backMenuKeyboard());
            return;
        }

        Optional<Sale> saleOpt = service.findSale(saleId);
        if (saleOpt.isEmpty()) {
            sendText(user.tgId(), "⚠️ Заявка не найдена.", backMenuKeyboard());
            return;
        }

        Sale sale = saleOpt.get();
        if (sale.status() != SaleStatus.PENDING) {
            sendText(user.tgId(), "ℹ️ Заявка уже обработана.", backMenuKeyboard());
            return;
        }

        if (!service.canReviewSale(user, sale)) {
            sendText(user.tgId(), "⛔ Нет прав на подтверждение этой заявки.", backMenuKeyboard());
            return;
        }

        service.confirmSale(user, sale);

        User buyer = service.getUserById(sale.buyerUserId());
        User seller = service.getUserById(sale.sellerUserId());
        sendText(
                buyer.tgId(),
                "✅ Оплата подтверждена. Уровень " + sale.level() + " активирован.",
                mainMenuKeyboard(service.refreshUser(buyer))
        );
        sendText(seller.tgId(), "✅ Вы подтвердили оплату по заявке #" + sale.id(), backMenuKeyboard());

        if (service.isAdmin(user) && user.id() != seller.id()) {
            sendText(user.tgId(), "✅ Заявка #" + sale.id() + " подтверждена.", backMenuKeyboard());
        }
    }

    private void rejectSale(User user, String data) throws TelegramApiException {
        long saleId = parseLongId(data, "sale_no:");
        if (saleId <= 0) {
            sendText(user.tgId(), "⚠️ Некорректный id заявки.", backMenuKeyboard());
            return;
        }

        Optional<Sale> saleOpt = service.findSale(saleId);
        if (saleOpt.isEmpty()) {
            sendText(user.tgId(), "⚠️ Заявка не найдена.", backMenuKeyboard());
            return;
        }

        Sale sale = saleOpt.get();
        if (sale.status() != SaleStatus.PENDING) {
            sendText(user.tgId(), "ℹ️ Заявка уже обработана.", backMenuKeyboard());
            return;
        }

        if (!service.canReviewSale(user, sale)) {
            sendText(user.tgId(), "⛔ Нет прав на отклонение этой заявки.", backMenuKeyboard());
            return;
        }

        service.rejectSale(user, sale, "Отклонено наставником/админом");

        User buyer = service.getUserById(sale.buyerUserId());
        sendText(
                buyer.tgId(),
                "❌ Оплата по заявке #" + sale.id() + " отклонена. Проверьте реквизиты и отправьте чек заново.",
                mainMenuKeyboard(service.refreshUser(buyer))
        );
        sendText(user.tgId(), "❌ Заявка #" + sale.id() + " отклонена.", backMenuKeyboard());
    }

    private void sendProofWithCaption(long chatId, Sale sale, String caption, InlineKeyboardMarkup kb) throws TelegramApiException {
        if ("PHOTO".equalsIgnoreCase(sale.proofType())) {
            SendPhoto photo = new SendPhoto();
            photo.setChatId(chatId);
            photo.setPhoto(new InputFile(sale.proofFileId()));
            photo.setCaption(caption);
            photo.setReplyMarkup(kb);
            execute(photo);
        } else {
            SendDocument document = new SendDocument();
            document.setChatId(chatId);
            document.setDocument(new InputFile(sale.proofFileId()));
            document.setCaption(caption);
            document.setReplyMarkup(kb);
            execute(document);
        }
    }

    private String buildSaleReviewText(Sale sale, User seller, User buyer) {
        return "🧾 Заявка #" + sale.id() + "\n"
                + "Уровень: " + sale.level() + "\n"
                + "Покупатель: " + displayUser(buyer) + " (tgId=" + buyer.tgId() + ")\n"
                + "Наставник: " + displayUser(seller) + " (tgId=" + seller.tgId() + ")\n"
                + "Статус: " + sale.status();
    }

    private InlineKeyboardMarkup mainMenuKeyboard(User user) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                button(buttonText(TEXT_BTN_TEAM, "👥 Моя команда"), "team"),
                button(buttonText(TEXT_BTN_INVITE, "🔗 Пригласить"), "invite")
        ));
        rows.add(List.of(
                button(buttonText(TEXT_BTN_PROGRESS, "📈 Мой прогресс"), "progress"),
                button(buttonText(TEXT_BTN_BUY, "🛒 Купить уровень"), "buy")
        ));
        rows.add(List.of(button(buttonText(TEXT_BTN_MENTOR_CONTACTS, "📇 Контакты наставника"), "mentor_contacts")));
        rows.add(List.of(button(buttonText(TEXT_BTN_MENTOR_UNREACHABLE, "🚨 Наставник не выходит на связь"), "mentor_unreachable")));
        rows.add(List.of(button(buttonText(TEXT_BTN_PENDING, "💸 Новые оплаты (проверка)"), "pending")));
        rows.add(List.of(
                button(buttonText(TEXT_BTN_ABOUT, "ℹ️ О проекте"), "about"),
                button(buttonText(TEXT_BTN_SUPPORT, "🛟 Помощь"), "support")
        ));

        if (service.isAdmin(user)) {
            rows.add(List.of(button(buttonText(TEXT_BTN_ADMIN, "🛠 Админ-панель"), "admin")));
        }

        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup backMenuKeyboard() {
        return new InlineKeyboardMarkup(List.of(List.of(button("🏠 В меню", "menu"))));
    }

    private InlineKeyboardMarkup unauthKeyboard() {
        return new InlineKeyboardMarkup(List.of(List.of(button("🛟 Помощь", "support"))));
    }

    private InlineKeyboardMarkup emailRequiredKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("✏️ Указать e-mail", "set_email")),
                List.of(button("🛟 Помощь", "support"))
        ));
    }

    private InlineKeyboardMarkup adminTextsKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("✍️ Стартовое меню", "edit_text:" + TEXT_START)),
                List.of(button("✍️ О проекте", "edit_text:" + TEXT_ABOUT)),
                List.of(button("✍️ Помощь", "edit_text:" + TEXT_SUPPORT)),
                List.of(button("✍️ Текст без реф-ссылки", "edit_text:" + TEXT_REF_REQUIRED)),
                List.of(button("✍️ Подсказка в контактах наставника", "edit_text:" + TEXT_MENTOR_CONTACTS_HINT)),
                List.of(button("✍️ Кнопка: Моя команда", "edit_text:" + TEXT_BTN_TEAM)),
                List.of(button("✍️ Кнопка: Пригласить", "edit_text:" + TEXT_BTN_INVITE)),
                List.of(button("✍️ Кнопка: Мой прогресс", "edit_text:" + TEXT_BTN_PROGRESS)),
                List.of(button("✍️ Кнопка: Купить уровень", "edit_text:" + TEXT_BTN_BUY)),
                List.of(button("✍️ Кнопка: Контакты наставника", "edit_text:" + TEXT_BTN_MENTOR_CONTACTS)),
                List.of(button("✍️ Кнопка: Наставник не на связи", "edit_text:" + TEXT_BTN_MENTOR_UNREACHABLE)),
                List.of(button("✍️ Кнопка: Новые оплаты", "edit_text:" + TEXT_BTN_PENDING)),
                List.of(button("✍️ Кнопка: О проекте", "edit_text:" + TEXT_BTN_ABOUT)),
                List.of(button("✍️ Кнопка: Помощь", "edit_text:" + TEXT_BTN_SUPPORT)),
                List.of(button("✍️ Кнопка: Админ-панель", "edit_text:" + TEXT_BTN_ADMIN)),
                List.of(button("⬅️ В админ-панель", "admin"))
        ));
    }

    private InlineKeyboardMarkup textEditKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("✅ Готово", "done_text_edit"), button("❌ Отмена", "cancel_text_edit"))
        ));
    }

    private InlineKeyboardMarkup aboutKeyboard(int page, int total) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (total > 1) {
            List<InlineKeyboardButton> nav = new ArrayList<>();
            if (page > 0) {
                nav.add(button("⬅️ " + page + " часть", "about_page:" + (page - 1)));
            }
            if (page < total - 1) {
                nav.add(button((page + 2) + " часть ➡️", "about_page:" + (page + 1)));
            }
            if (!nav.isEmpty()) {
                rows.add(nav);
            }
        }
        rows.add(List.of(button("🏠 В меню", "menu")));
        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup saleReviewKeyboard(long saleId) {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("✅ Подтвердить", "sale_ok:" + saleId), button("❌ Отклонить", "sale_no:" + saleId)),
                List.of(button("🏠 В меню", "menu"))
        ));
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(text);
        b.setCallbackData(callbackData);
        return b;
    }

    private void sendMainMenu(User user, String text) throws TelegramApiException {
        sendMainMenuWithPhoto(user.tgId(), text, mainMenuKeyboard(service.refreshUser(user)));
    }

    private void sendMainMenuWithPhoto(long chatId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        SendPhoto photo = new SendPhoto();
        photo.setChatId(chatId);
        photo.setCaption(text);
        photo.setReplyMarkup(keyboard);

        if (cachedStartMenuPhotoFileId != null && !cachedStartMenuPhotoFileId.isBlank()) {
            photo.setPhoto(new InputFile(cachedStartMenuPhotoFileId));
            execute(photo);
            return;
        }

        if (!Files.exists(START_MENU_PHOTO_PATH)) {
            sendText(chatId, text, keyboard);
            return;
        }

        photo.setPhoto(new InputFile(START_MENU_PHOTO_PATH.toFile()));
        Message sent = execute(photo);
        if (sent != null && sent.hasPhoto() && !sent.getPhoto().isEmpty()) {
            PhotoSize best = sent.getPhoto().stream()
                    .max(Comparator.comparing(PhotoSize::getFileSize))
                    .orElse(sent.getPhoto().get(0));
            cachedStartMenuPhotoFileId = best.getFileId();
            service.setText(START_MENU_PHOTO_FILE_ID_KEY, cachedStartMenuPhotoFileId);
        }
    }

    private void sendText(long chatId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        msg.setReplyMarkup(keyboard);
        execute(msg);
    }

    private void answerCallback(String callbackId, String text) {
        try {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackId);
            answer.setText(text);
            execute(answer);
        } catch (Exception ignored) {
        }
    }

    private String extractStartPayload(String text) {
        if (text == null || !text.startsWith("/start")) {
            return null;
        }
        String[] parts = text.split("\\s+", 2);
        return parts.length > 1 ? parts[1].trim() : null;
    }

    private int parseIntId(String value, String prefix) {
        try {
            return Integer.parseInt(value.substring(prefix.length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private long parseLongId(String value, String prefix) {
        try {
            return Long.parseLong(value.substring(prefix.length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private String displayUser(User user) {
        if (user.firstName() != null && !user.firstName().isBlank()) {
            return user.firstName();
        }
        if (user.username() != null && !user.username().isBlank()) {
            return "@" + user.username();
        }
        return "Пользователь " + user.id();
    }

    private String telegramContact(User user) {
        if (user.username() != null && !user.username().isBlank()) {
            return "@" + user.username();
        }
        return "tgId=" + user.tgId();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "не указано" : value;
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private boolean isUnauthorizedWithoutRef(User user) {
        return !service.isAdmin(user)
                && !service.isSystemUpline(user)
                && !service.isBootstrapChainUser(user)
                && user.sponsorUserId() == null
                && user.purchasedLevel() <= 0;
    }

    private boolean requiresEmail(User user) {
        return user.email() == null || user.email().isBlank();
    }

    private String defaultRefRequiredText() {
        return """
                Для пользования ботом необходима реферальная ссылка.
                по всем вопросам: @Gerhard_Stein
                """;
    }

    private String buttonText(String key, String defaultValue) {
        return service.getText(key, defaultValue);
    }

    private boolean isEditableTextKey(String key) {
        return List.of(
                TEXT_START, TEXT_ABOUT, TEXT_SUPPORT, TEXT_REF_REQUIRED, TEXT_MENTOR_CONTACTS_HINT,
                TEXT_BTN_TEAM, TEXT_BTN_INVITE, TEXT_BTN_PROGRESS, TEXT_BTN_BUY, TEXT_BTN_MENTOR_CONTACTS,
                TEXT_BTN_MENTOR_UNREACHABLE, TEXT_BTN_PENDING, TEXT_BTN_ABOUT, TEXT_BTN_SUPPORT, TEXT_BTN_ADMIN
        ).contains(key);
    }

    private String encodeTextEditPayload(String key, List<String> parts) {
        StringBuilder sb = new StringBuilder(key == null ? "" : key);
        for (String part : parts) {
            String encoded = Base64.getEncoder()
                    .encodeToString((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
            sb.append('\t').append(encoded);
        }
        return sb.toString();
    }

    private TextEditPayload decodeTextEditPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return new TextEditPayload("", List.of());
        }

        String[] chunks = payload.split("\t", -1);
        String key = chunks[0] == null ? "" : chunks[0].trim();
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < chunks.length; i++) {
            String chunk = chunks[i];
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            try {
                String value = new String(Base64.getDecoder().decode(chunk), StandardCharsets.UTF_8);
                if (!value.isBlank()) {
                    parts.add(value);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new TextEditPayload(key, parts);
    }

    private String previewText(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "ℹ️ Текст пока пуст.";
        }
        String cleaned = text.trim();
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        return cleaned.substring(0, maxLen) + "\n\n…";
    }

    private List<String> splitIntoPages(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return List.of("ℹ️ Раздел пока пуст.");
        }

        List<String> pages = new ArrayList<>();
        String remaining = text.trim();
        while (remaining.length() > maxLen) {
            int cut = remaining.lastIndexOf('\n', maxLen);
            if (cut < maxLen / 2) {
                cut = remaining.lastIndexOf(' ', maxLen);
            }
            if (cut < maxLen / 2) {
                cut = maxLen;
            }
            pages.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isBlank()) {
            pages.add(remaining);
        }
        return pages.isEmpty() ? List.of("ℹ️ Раздел пока пуст.") : pages;
    }

    private record TextEditPayload(String key, List<String> parts) {
    }
}
