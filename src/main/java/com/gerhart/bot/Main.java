package com.gerhart.bot;

import com.gerhart.bot.bot.AutoGoodsBot;
import com.gerhart.bot.config.AppConfig;
import com.gerhart.bot.db.Database;
import com.gerhart.bot.db.dao.SaleDao;
import com.gerhart.bot.db.dao.UserDao;
import com.gerhart.bot.service.BotService;
import com.gerhart.bot.state.StateStore;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnv();

        Database database = new Database(config.dbUrl());
        database.init();

        UserDao userDao = new UserDao(database);
        SaleDao saleDao = new SaleDao(database);
        StateStore stateStore = new StateStore(database);
        BotService service = new BotService(userDao, saleDao, config);

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new AutoGoodsBot(config, service, stateStore));

        System.out.println("Bot started: @" + config.botUsername());
    }
}
