package com.gerhart.bot.service;

import com.gerhart.bot.config.AppConfig;
import com.gerhart.bot.db.dao.MentorOverrideDao;
import com.gerhart.bot.db.dao.SaleDao;
import com.gerhart.bot.db.dao.UserDao;
import com.gerhart.bot.model.Role;
import com.gerhart.bot.model.Sale;
import com.gerhart.bot.model.User;

import java.util.List;
import java.util.Optional;

public class BotService {
    private final UserDao userDao;
    private final SaleDao saleDao;
    private final MentorOverrideDao mentorOverrideDao;
    private final AppConfig config;

    public BotService(UserDao userDao, SaleDao saleDao, MentorOverrideDao mentorOverrideDao, AppConfig config) {
        this.userDao = userDao;
        this.saleDao = saleDao;
        this.mentorOverrideDao = mentorOverrideDao;
        this.config = config;
    }

    public User ensureUser(long tgId, String username, String firstName, String startPayload) {
        Optional<User> existing = userDao.findByTgId(tgId);
        if (existing.isPresent()) {
            userDao.updateProfile(tgId, username, firstName);
            return userDao.findByTgId(tgId).orElseThrow();
        }

        Long sponsorUserId = resolveSponsorUserId(startPayload);
        Role role = config.adminTgIds().contains(tgId) ? Role.ADMIN : Role.USER;
        return userDao.create(tgId, username, firstName, sponsorUserId, role);
    }

    public boolean isAdmin(User user) {
        return user.role() == Role.ADMIN || config.adminTgIds().contains(user.tgId());
    }

    public String getInviteLink(User user) {
        return "https://t.me/" + config.botUsername() + "?start=ref_" + user.tgId();
    }

    public int getDirectReferralLimit(User user) {
        if (user.purchasedLevel() < 1) {
            return 0;
        }
        return user.purchasedLevel() >= 2 ? Integer.MAX_VALUE : 3;
    }

    public boolean canAcceptNewDirectReferral(User user) {
        if (user.purchasedLevel() < 1) {
            return false;
        }
        if (user.purchasedLevel() >= 2) {
            return true;
        }
        return userDao.countDirectReferrals(user.id()) < 3;
    }

    public int getNextLevel(User user) {
        int next = user.purchasedLevel() + 1;
        return next <= config.maxLevel() ? next : -1;
    }

    public Optional<User> findMentorForLevel(User buyer, int level) {
        Optional<Long> overrideSellerId = mentorOverrideDao.findOverrideSellerId(buyer.id(), level);
        if (overrideSellerId.isPresent()) {
            Optional<User> overrideSeller = userDao.findById(overrideSellerId.get());
            if (overrideSeller.isPresent() && canSellerSellLevel(overrideSeller.get(), level)) {
                return overrideSeller;
            }
        }

        if (level < 1) {
            return Optional.empty();
        }

        Optional<User> current = userDao.findById(buyer.id());
        int depth = 0;
        while (current.isPresent() && current.get().sponsorUserId() != null) {
            User sponsor = userDao.findById(current.get().sponsorUserId()).orElse(null);
            if (sponsor == null) {
                return Optional.empty();
            }

            depth++;
            if (depth >= (level - 1) && canSellerSellLevel(sponsor, level)) {
                return Optional.of(sponsor);
            }
            current = Optional.of(sponsor);
        }
        return Optional.empty();
    }

    public boolean canSellerSellLevel(User seller, int level) {
        if (level < 1 || level > config.maxLevel()) {
            return false;
        }
        if (level == 1) {
            return seller.purchasedLevel() >= 1 && canAcceptNewDirectReferral(seller);
        }
        if (seller.purchasedLevel() < level) {
            return false;
        }

        // Пока наставник не купил следующий уровень, на текущем уровне максимум 3 продажи.
        if (seller.purchasedLevel() == level) {
            return saleDao.countConfirmedSalesBySellerAndLevel(seller.id(), level) < 3;
        }
        return true;
    }

    public Optional<String> validateCanBuyNextLevel(User buyer) {
        int next = getNextLevel(buyer);
        if (next == -1) {
            return Optional.of("Вы уже открыли максимальный уровень.");
        }
        if (saleDao.findPendingForBuyerAndLevel(buyer.id(), next).isPresent()) {
            return Optional.of("У вас уже есть заявка на этот уровень в ожидании проверки.");
        }

        Optional<User> mentorOpt = findMentorForLevel(buyer, next);
        if (mentorOpt.isEmpty()) {
            return Optional.of("Наставник для этого уровня не найден. Обратитесь в поддержку.");
        }

        User mentor = mentorOpt.get();
        if (!canSellerSellLevel(mentor, next)) {
            return Optional.of("У наставника достигнут лимит продаж этого уровня. Обратитесь в поддержку.");
        }

        return Optional.empty();
    }

    public Sale createPendingSale(User buyer, int level, String proofType, String proofFileId) {
        User mentor = findMentorForLevel(buyer, level).orElseThrow();
        return saleDao.create(mentor.id(), buyer.id(), level, proofType, proofFileId);
    }

    public Optional<Sale> findSale(long saleId) {
        return saleDao.findById(saleId);
    }

    public boolean canReviewSale(User reviewer, Sale sale) {
        if (isAdmin(reviewer)) {
            return true;
        }
        return reviewer.id() == sale.sellerUserId();
    }

    public void confirmSale(User reviewer, Sale sale) {
        saleDao.confirm(sale.id(), reviewer.id());
        User buyer = userDao.findById(sale.buyerUserId()).orElseThrow();
        if (buyer.purchasedLevel() < sale.level()) {
            userDao.setPurchasedLevel(buyer.id(), sale.level());
        }
    }

    public void rejectSale(User reviewer, Sale sale, String reason) {
        saleDao.reject(sale.id(), reviewer.id(), reason);
    }

    public List<Sale> listPendingForUser(User user, int limit) {
        if (isAdmin(user)) {
            return saleDao.listPendingGlobal(limit);
        }
        return saleDao.listPendingBySeller(user.id(), limit);
    }

    public List<User> listRecentUsers(int limit) {
        return userDao.listUsers(limit);
    }

    public int countAllUsers() {
        return userDao.countAllUsers();
    }

    public int countAllSales() {
        return saleDao.countAllSales();
    }

    public int countPendingSales() {
        return saleDao.countPendingSales();
    }

    public int countConfirmedSalesAtLevel(User user, int level) {
        return saleDao.countConfirmedSalesBySellerAndLevel(user.id(), level);
    }

    public int countDirectReferrals(User user) {
        return userDao.countDirectReferrals(user.id());
    }

    public List<User> getDirectReferrals(User user, int limit) {
        return userDao.getDirectReferrals(user.id(), limit);
    }

    public void setEmail(User user, String email) {
        userDao.setEmail(user.id(), email);
    }

    public void setPaymentDetails(User user, String paymentDetails) {
        userDao.setPaymentDetails(user.id(), paymentDetails);
    }

    public User refreshUser(User user) {
        return userDao.findById(user.id()).orElseThrow();
    }

    public User getUserById(long id) {
        return userDao.findById(id).orElseThrow();
    }

    public Optional<EscalationResult> escalateMentor(User buyer, int level) {
        Optional<User> currentMentorOpt = findMentorForLevel(buyer, level);
        if (currentMentorOpt.isEmpty()) {
            return Optional.empty();
        }

        User currentMentor = currentMentorOpt.get();
        User cursor = currentMentor;
        while (cursor.sponsorUserId() != null) {
            User candidate = userDao.findById(cursor.sponsorUserId()).orElse(null);
            if (candidate == null) {
                break;
            }
            if (canSellerSellLevel(candidate, level)) {
                mentorOverrideDao.upsertOverride(buyer.id(), level, candidate.id());
                return Optional.of(new EscalationResult(currentMentor, candidate));
            }
            cursor = candidate;
        }
        return Optional.empty();
    }

    private Long resolveSponsorUserId(String startPayload) {
        if (startPayload == null || startPayload.isBlank() || !startPayload.startsWith("ref_")) {
            return null;
        }

        try {
            long sponsorTgId = Long.parseLong(startPayload.substring(4));
            Optional<User> sponsor = userDao.findByTgId(sponsorTgId);
            if (sponsor.isEmpty()) {
                return null;
            }

            if (!canAcceptNewDirectReferral(sponsor.get())) {
                return null;
            }

            return sponsor.get().id();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record EscalationResult(User previousMentor, User newMentor) {
    }
}
