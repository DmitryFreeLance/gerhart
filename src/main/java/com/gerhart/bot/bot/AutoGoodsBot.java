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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class AutoGoodsBot extends TelegramLongPollingBot {
    private static final String STATE_AWAIT_EMAIL = "AWAIT_EMAIL";
    private static final String STATE_AWAIT_PAYMENT = "AWAIT_PAYMENT";
    private static final String STATE_AWAIT_PROOF = "AWAIT_PROOF";

    private final AppConfig config;
    private final BotService service;
    private final StateStore stateStore;

    public AutoGoodsBot(AppConfig config, BotService service, StateStore stateStore) {
        super(config.botToken());
        this.config = config;
        this.service = service;
        this.stateStore = stateStore;
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

        if (text != null && text.startsWith("/start")) {
            sendMainMenu(user, """
                    🚗 Добро пожаловать в клуб автотоваров!

                    Здесь вы сможете:
                    • развивать свою команду;
                    • отслеживать прогресс по уровням;
                    • покупать новый уровень и отправлять чек;
                    • получать выплаты напрямую по своим реквизитам.

                    Выберите нужный раздел ниже 👇
                    """);
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
                service.setEmail(user, message.getText().trim());
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

        if (data == null) {
            answerCallback(callbackQuery.getId(), "⚠️ Пустая команда");
            return;
        }

        switch (data) {
            case "menu" -> sendMainMenu(user, "🏠 Главное меню:");
            case "team" -> sendTeam(user);
            case "invite" -> sendInvite(user);
            case "progress" -> sendProgress(user);
            case "buy" -> sendBuyLevel(user);
            case "mentor_contacts" -> sendMentorContacts(user);
            case "support" -> sendSupport(user);
            case "payment" -> sendPaymentProfile(user);
            case "set_email" -> {
                stateStore.setState(user.tgId(), STATE_AWAIT_EMAIL, null);
                sendText(user.tgId(), "📧 Отправьте e-mail одним сообщением.", backMenuKeyboard());
            }
            case "set_payment" -> {
                stateStore.setState(user.tgId(), STATE_AWAIT_PAYMENT, null);
                sendText(user.tgId(), "💳 Отправьте платежные реквизиты (банк, карта/счет, ФИО и т.д.).", backMenuKeyboard());
            }
            case "pending" -> sendPendingPayments(user);
            case "admin" -> sendAdminPanel(user);
            case "admin_users" -> sendAdminUsers(user);
            case "admin_stats" -> sendAdminStats(user);
            default -> {
                if (data.startsWith("proof_start:")) {
                    startProofUpload(user, data);
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
        if (limit == Integer.MAX_VALUE) {
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
        sb.append("Платежные реквизиты: ").append(nullToDash(mentor.paymentDetails())).append("\n\n");
        sb.append("После оплаты загрузите чек кнопкой ниже 👇");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(button("🧾 Загрузить чек", "proof_start:" + next)),
                List.of(button("⬅️ Назад", "menu"))
        ));

        sendText(user.tgId(), sb.toString(), kb);
    }

    private void sendMentorContacts(User user) throws TelegramApiException {
        int next = service.getNextLevel(user);
        if (next == -1) {
            sendText(user.tgId(), "Максимальный уровень уже открыт. Дополнительные контакты не требуются.", backMenuKeyboard());
            return;
        }

        Optional<User> mentorOpt = service.findMentorForLevel(user, next);
        if (mentorOpt.isEmpty()) {
            sendText(user.tgId(), "Наставник для следующего уровня не найден. Обратитесь в поддержку.", backMenuKeyboard());
            return;
        }

        User mentor = mentorOpt.get();
        String text = "📇 Контакты наставника для уровня " + next + "\n\n"
                + "Имя: " + displayUser(mentor) + "\n"
                + "Telegram: " + telegramContact(mentor) + "\n"
                + "E-mail: " + nullToDash(mentor.email()) + "\n"
                + "Реквизиты: " + nullToDash(mentor.paymentDetails());

        sendText(user.tgId(), text, backMenuKeyboard());
    }

    private void sendSupport(User user) throws TelegramApiException {
        sendText(user.tgId(), "🛟 Поддержка: " + config.supportContact(), backMenuKeyboard());
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
        rows.add(List.of(button("👥 Моя команда", "team"), button("🔗 Пригласить", "invite")));
        rows.add(List.of(button("📈 Мой прогресс", "progress"), button("🛒 Купить уровень", "buy")));
        rows.add(List.of(button("📇 Контакты наставника", "mentor_contacts"), button("💼 Мои платежные данные", "payment")));
        rows.add(List.of(button("💸 Новые оплаты", "pending")));
        rows.add(List.of(button("🛟 Поддержка", "support")));

        if (service.isAdmin(user)) {
            rows.add(List.of(button("🛠 Админ-панель", "admin")));
        }

        return new InlineKeyboardMarkup(rows);
    }

    private InlineKeyboardMarkup backMenuKeyboard() {
        return new InlineKeyboardMarkup(List.of(List.of(button("🏠 В меню", "menu"))));
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
        sendText(user.tgId(), text, mainMenuKeyboard(service.refreshUser(user)));
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
}
