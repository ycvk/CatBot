package org.Simbot.plugins.gameSearch;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import love.forte.simboot.annotation.Filter;
import love.forte.simboot.annotation.Listener;
import love.forte.simboot.filter.MatchType;
import love.forte.simbot.component.mirai.message.MiraiForwardMessageBuilder;
import love.forte.simbot.event.GroupMessageEvent;
import love.forte.simbot.message.Messages;
import love.forte.simbot.message.MessagesBuilder;
import love.forte.simbot.resources.Resource;
import net.mamoe.mirai.message.data.ForwardMessage;
import org.Simbot.config.threadpool.IOThreadPool;
import org.Simbot.plugins.gameSearch.entity.*;
import org.Simbot.utils.AsyncHttpClientUtil;
import org.Simbot.utils.SendMsgUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * @author ：ycvk
 * @description ：游戏搜索监听器
 * @date ：2023/08/11 15:57
 */
@Slf4j
@Component
public class GameSearchListener {

    @Listener
    @Filter(value = "/game ", matchType = MatchType.TEXT_STARTS_WITH)
    @SneakyThrows
    public void getAVMessage(@NotNull final GroupMessageEvent event) {
        //获取用户输入的内容
        final String next = event.getMessageContent().getPlainText().substring(6);
        final var messageReceipt = SendMsgUtil.sendReplyGroupMsg(event, "正在检索中，请稍候");
        final BlackBoxSearchEntity searchEntity = SteamSearchScraper.searchByName(next);
        SendMsgUtil.withdrawMessage(messageReceipt, 15);
        if (searchEntity == null) {
            SendMsgUtil.sendReplyGroupMsg(event, "未找到相关游戏");
            return;
        }
        //获取游戏图片
        final var stream = IOThreadPool.submit(() -> AsyncHttpClientUtil.downloadImage(searchEntity.getImage(), false, true, 0.5f));

        //构建转发消息链
        final var chain = new MiraiForwardMessageBuilder(ForwardMessage.DisplayStrategy.Default);

        final int appId = searchEntity.getSteamAppid();
        //如果是主机游戏，直接返回
        if ("console".equals(searchEntity.getGameType())) {
            final Messages messages = SteamSearchScraper.searchConsoleGame(searchEntity);
            chain.add(event.getBot(), messages);
            event.getSource().sendBlocking(chain.build());
            return;
        }
        //获取游戏信息
        final GameInfo gameInfo = SteamSearchScraper.searchByGameId(appId);
        //获取游戏简介
        final var introduction = IOThreadPool.submit(() -> SteamSearchScraper.searchIntroductionById(appId));
        final MessagesBuilder builder = new MessagesBuilder();
        //获取游戏价格
        final GamePrice price = gameInfo.getPrice();
        //获取游戏dlc列表
        final List<GameDlc> dlcs = gameInfo.getDlcs();
        //获取游戏在线数据
        final List<GameOnlineData> onlineData = gameInfo.getGameOnlineData();
        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("游戏名称：").append(gameInfo.getName()).append("\n")
                .append("steam链接：").append("https://store.steampowered.com/app/").append(appId).append("\n")
                .append("游戏类型：").append(gameInfo.getGenres().stream().reduce((a, b) -> a + " " + b).orElse("")).append("\n")
                .append("游戏评分：").append(Optional.ofNullable(searchEntity.getScore()).orElse(searchEntity.getScoreDesc())).append("\n")
                .append(gameInfo.getPositiveDesc()).append("\n")//好评率
                .append("steam整体评价：").append(gameInfo.getGameReviewSummary()).append("\n")
                .append("是否支持中文：").append(gameInfo.getSupportChinese() == 1 ? "是" : "否").append("\n")
        ;
        Optional.ofNullable(gameInfo.getNameEn()).ifPresent(nameEn -> stringBuilder.append("英文名称：").append(nameEn).append("\n"));

        builder.image(Resource.of(stream.get(5, TimeUnit.SECONDS)));
        builder.text(stringBuilder.toString());
        chain.add(event.getBot(), builder.build());

        //免费游戏不显示价格, 比如apex
        if (price != null) {
            final MessagesBuilder priceBuilder = buildPriceBuilder(price);
            chain.add(event.getBot(), priceBuilder.build());
        }
        //如果有在线数据，显示在线数据
        if (CollUtil.isNotEmpty(onlineData)) {
            final MessagesBuilder onlineBuilder = buildOnlineList(onlineData);
            chain.add(event.getBot(), onlineBuilder.build());
        }
        //如果有dlc，显示dlc列表
        if (CollUtil.isNotEmpty(dlcs)) {
            chain.add(event.getBot(), "↘ ↓ ↓DLC列表↓ ↓ ↙\n");
            final List<MessagesBuilder> dlcList = buildDlcList(dlcs);
            dlcList.parallelStream().forEach(dlc -> chain.add(event.getBot(), dlc.build()));
        }
        //如果有截图，显示截图
        if (CollUtil.isNotEmpty(gameInfo.getScreenshots())) {
            final MessagesBuilder gameScreenshotList = buildGameScreenshotList(gameInfo.getScreenshots());
            if (gameScreenshotList != null) {
                chain.add(event.getBot(), "↘ ↓ ↓游戏截图↓ ↓ ↙\n");
                chain.add(event.getBot(), gameScreenshotList.build());
            }
        }
        chain.add(event.getBot(), "游戏简介：\n" + introduction.get(5, TimeUnit.SECONDS));

        event.getSource().sendBlocking(chain.build());
    }

    /**
     * 构建在线数据消息
     *
     * @param onlineData 在线数据
     * @return 消息
     */
    public MessagesBuilder buildOnlineList(final List<GameOnlineData> onlineData) {
        return onlineData.parallelStream()
                .map(this::buildOnlineBuilder)
                .reduce((a, b) -> {
                    a.append(b.build());
                    return a;
                }).orElse(null);
    }

    /**
     * 构建单个在线数据消息
     *
     * @param onlineData 在线数据
     * @return 消息
     */
    private MessagesBuilder buildOnlineBuilder(final GameOnlineData onlineData) {
        if (onlineData == null) {
            return null;
        }
        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(onlineData.getDesc()).append(" : ").append(onlineData.getValue()).append("\n");
        if (!"当前在线".equals(onlineData.getDesc())) {
            stringBuilder.append("排名 : ").append(onlineData.getRank()).append("\n\n");
        }
        final MessagesBuilder builder = new MessagesBuilder();
        builder.text(stringBuilder.toString());
        return builder;
    }

    /**
     * 构建价格消息
     *
     * @param price 价格
     * @return 消息
     */
    public MessagesBuilder buildPriceBuilder(final GamePrice price) {
        if (price == null) {
            return null;
        }
        final MessagesBuilder priceBuilder = new MessagesBuilder();
        final StringBuilder builder = new StringBuilder();
        builder.append("steam国区目前价格：").append(Optional.ofNullable(price.getCurrent()).orElse("此区域未找到价格")).append("\n");
        if (0 != price.getDiscount()) {
            builder.append("steam目前折扣        ：").append("-").append(price.getDiscount()).append("%").append("\n")
                    .append("是否为史低折扣        ：").append(price.getIsLowest() == 0 ? "否" : "是").append("\n")
                    .append("折扣剩余时间           ：").append(Optional.ofNullable(price.getDeadlineDate()).orElse("商家未明确结束时间")).append("\n")
            ;
        }
        final var lowestPrice = Optional.ofNullable(price.getLowestPriceRaw()).orElse(String.valueOf(price.getLowestPrice()));
        builder.append("steam历史最低价    ：").append(lowestPrice).append("\n")
                .append("steam历史最低折扣：").append("-").append(price.getLowestDiscount()).append("%").append("\n");
        priceBuilder.text(builder.toString());
        return priceBuilder;

    }

    /**
     * 构建DLC消息列表
     *
     * @param dlcs dlc列表
     * @return 消息列表
     */
    public List<MessagesBuilder> buildDlcList(final List<GameDlc> dlcs) {
        return dlcs.parallelStream()
                .filter(dlc -> StrUtil.isNotBlank(dlc.getName()))
                .map(this::buildDlcBuilder).toList();
    }

    /**
     * 构建单个DLC消息
     *
     * @param dlc dlc
     * @return 消息
     */
    @SneakyThrows
    private MessagesBuilder buildDlcBuilder(final GameDlc dlc) {
        final MessagesBuilder builder = new MessagesBuilder();
        final ByteArrayInputStream stream = AsyncHttpClientUtil.downloadImage(dlc.getImage(), false, true, 0.5f);
        final GamePrice price = dlc.getPrice();
        final MessagesBuilder priceBuilder = buildPriceBuilder(price);
        Optional.ofNullable(stream).ifPresent(s -> {
            try {
                builder.image(Resource.of(stream)).text("\n");
            } catch (final IOException e) {
                log.error("此dlc没有图片或图片下载失败", e);
            }
        });
        builder.text("DLC名称：").text(dlc.getName()).text("\n")
        ;
        if (priceBuilder != null) {
            builder.append(priceBuilder.build());
        }
        return builder;
    }

    /**
     * 构建游戏截图消息列表
     *
     * @param screenshots 游戏截图列表
     * @return 消息列表
     */
    private MessagesBuilder buildGameScreenshotList(final List<GameScreenshot> screenshots) {
        return Optional.ofNullable(screenshots)
                .orElse(Collections.emptyList())
                .subList(0, Math.min(screenshots.size(), 8))
                .parallelStream()
                .map(this::buildGameScreenshot)
                .reduce((a, b) -> {
                    a.append(b.build());
                    return a;
                }).orElse(null);
    }

    /**
     * 构建游戏截图消息
     *
     * @param gameScreenshot 游戏截图
     * @return 消息
     */
    private MessagesBuilder buildGameScreenshot(final GameScreenshot gameScreenshot) {
        final MessagesBuilder builder = new MessagesBuilder();
        final ByteArrayInputStream stream = AsyncHttpClientUtil.downloadImage(gameScreenshot.getThumbnail(), false, true, 0.5f);
        Optional.ofNullable(stream).ifPresent(s -> {
            try {
                builder.image(Resource.of(stream)).text("\n");
            } catch (final IOException e) {
                log.error("此游戏没有图片或图片下载失败", e);
            }
        });
        return builder;
    }
}
