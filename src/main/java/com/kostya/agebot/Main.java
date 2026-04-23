package com.kostya.agebot;

import com.kostya.agebot.bot.AgeGateBot;
import com.kostya.agebot.config.BotConfig;
import com.kostya.agebot.db.Database;
import com.kostya.agebot.db.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        BotConfig config = BotConfig.fromEnv();

        Database database = new Database(config.dbPath());
        database.init();

        Repository repository = new Repository(database);
        repository.bootstrapAdmins(config.initialAdminIds());
        repository.setInitialGroupIdIfMissing(config.defaultGroupId());

        registerBot(config, repository);
        log.info("Bot started successfully");

        Thread.currentThread().join();
    }

    private static void registerBot(BotConfig config, Repository repository) throws TelegramApiException {
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        telegramBotsApi.registerBot(new AgeGateBot(config, repository));
    }
}
