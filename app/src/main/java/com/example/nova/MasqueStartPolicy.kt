package com.example.nova

/**
 * Решение о том, запускать ли фазу MASQUE в начале цикла подключения.
 *
 * Логика вынесена из [NovaVpnService] отдельно по одной причине: именно здесь
 * возник дефект, из-за которого выбранный пользователем MASQUE молча не
 * запускался. Быстрый старт откладывал получение identity «до первого реального
 * fallback», а фаза MASQUE без identity просто не начиналась — снаружи это
 * выглядело как подключение по WARP со счётчиком встроенных профилей.
 *
 * Правило, которое здесь закреплено: **явный выбор пользователя сильнее любой
 * эвристики быстрого старта**. Догадки о том, что выгоднее попробовать первым,
 * имеют смысл только в режиме «Авто».
 */
object MasqueStartPolicy {

    /**
     * @param masqueChosenExplicitly в списке регионов выбран именно MASQUE
     * @param hasCachedIdentity есть готовый MASQUE identity, регистрация не нужна
     * @param hasStrongVerifiedMasque есть проверенные MASQUE-профили на удобных портах
     * @param hasUserImportedWarpProfiles есть импортированные пользователем WARP/AWG профили
     * @param messengerFastStart цикл оптимизирован под мессенджеры
     * @param aggressiveFastStart старт с плитки, где важна каждая секунда
     * @param underlyingNetworkMetered нижележащая сеть тарифицируемая (обычно мобильная)
     * @param restrictedMobileNetwork сеть с ограничениями, требующая немедленной маскировки
     * @param diagnosticsMode прогон диагностики WARP, MASQUE в нём не участвует
     * @param operaBootstrapCycle временный WARP-цикл ради поднятия Opera
     * @param cooldownActive недавние срывы data-plane на MASQUE
     * @param failureStreak сколько срывов MASQUE подряд накопилось
     * @param explicitLockoutFresh срывы были недавно, а не когда-то давно
     * @param hasFreshLastSuccess есть свежий успешный маршрут
     */
    data class Inputs(
        val masqueChosenExplicitly: Boolean,
        val hasCachedIdentity: Boolean,
        val hasStrongVerifiedMasque: Boolean,
        val hasUserImportedWarpProfiles: Boolean,
        val messengerFastStart: Boolean,
        val aggressiveFastStart: Boolean,
        val underlyingNetworkMetered: Boolean,
        val restrictedMobileNetwork: Boolean,
        val diagnosticsMode: Boolean,
        val operaBootstrapCycle: Boolean,
        val cooldownActive: Boolean,
        val failureStreak: Int = 0,
        val explicitLockoutFresh: Boolean = true,
        val hasFreshLastSuccess: Boolean,
    )

    /**
     * Столько срывов подряд означает, что MASQUE сломан не «сейчас на этой
     * сети», а вообще. Дальше уважать явный выбор — значит крутить цикл
     * подключения впустую.
     */
    const val EXPLICIT_CHOICE_FAILURE_LIMIT = 3

    /**
     * Столько срывов подряд означает, что MASQUE на этой сети не поднимется.
     *
     * Порог ниже, чем у явного выбора, и намеренно: в «Авто» MASQUE идёт первым по
     * догадке, а не по просьбе пользователя. Догадка имеет смысл, пока сбывается.
     * Проверка стоит около полуминуты (четыре кандидата по шесть секунд), и на сети,
     * которая режет QUIC, это время каждое подключение отнималось у выбранного
     * пользователем WARP/AWG: счётчик успевал показать «x/4» до того, как начинался
     * настоящий перебор встроенных профилей.
     */
    const val AUTO_FIRST_FAILURE_LIMIT = 2

    /**
     * @param masqueFirst начинать перебор с MASQUE, а не с WARP/AWG
     * @param deferForUserImported отложить identity ради импортированных профилей
     * @param deferForMessenger отложить identity ради стартовой chat-aware волны
     * @param deferForOrdinaryWifi отложить identity, чтобы не тормозить старт на обычном Wi-Fi
     * @param skipForCooldown пропустить MASQUE из-за недавних срывов
     * @param brokenDespiteExplicitChoice выбранный MASQUE срывается раз за разом,
     * и мы уступаем WARP, чтобы не крутить цикл переподключений
     * @param thoroughRegistration дать регистрации полный бюджет времени, а не быстрый
     * @param thoroughCandidateScan собирать кандидатов сканом, а не коротким списком
     * anycast-соседей
     */
    data class Decision(
        val masqueFirst: Boolean,
        val deferForUserImported: Boolean,
        val deferForMessenger: Boolean,
        val deferForOrdinaryWifi: Boolean,
        val skipForCooldown: Boolean,
        val brokenDespiteExplicitChoice: Boolean,
        val thoroughRegistration: Boolean,
        val thoroughCandidateScan: Boolean,
    ) {
        /** Готовить identity прямо сейчас или отложить до первого реального fallback. */
        val deferIdentityPreparation: Boolean
            get() = deferForUserImported || deferForMessenger || deferForOrdinaryWifi
    }

    fun decide(inputs: Inputs): Decision {
        val explicit = inputs.masqueChosenExplicitly

        // Сорвался подряд — значит, дело не в конкретной попытке, а в сети. Проверка
        // MASQUE стоит около полуминуты, и повторять её каждое подключение, отнимая
        // время у выбранного пользователем транспорта, незачем. Отсрочка временная:
        // счётчик срывов протухает, и полный цикл MASQUE вернётся сам.
        val autoBrokenByFailures = !explicit &&
            inputs.failureStreak >= AUTO_FIRST_FAILURE_LIMIT &&
            inputs.explicitLockoutFresh

        val masqueFirst = (
            explicit ||
                (
                    inputs.hasStrongVerifiedMasque &&
                        !inputs.hasUserImportedWarpProfiles &&
                        !inputs.restrictedMobileNetwork &&
                        !inputs.diagnosticsMode &&
                        !inputs.operaBootstrapCycle
                    )
            ) && !autoBrokenByFailures

        // Все три отсрочки — это оптимизации времени старта в режиме «Авто».
        // Явный выбор MASQUE их отменяет: пользователь просил именно этот
        // транспорт, и отложить его означает не запустить вовсе.
        val deferForUserImported = !explicit &&
            inputs.hasUserImportedWarpProfiles &&
            !inputs.diagnosticsMode &&
            !inputs.operaBootstrapCycle

        val deferForMessenger = !explicit &&
            inputs.messengerFastStart &&
            inputs.aggressiveFastStart &&
            !inputs.diagnosticsMode &&
            !inputs.operaBootstrapCycle &&
            !masqueFirst

        val deferForOrdinaryWifi = !explicit &&
            !inputs.hasCachedIdentity &&
            !inputs.underlyingNetworkMetered &&
            !inputs.restrictedMobileNetwork &&
            !inputs.diagnosticsMode &&
            !inputs.operaBootstrapCycle

        // Cooldown защищает от бесполезных попыток там, где MASQUE только что
        // срывался. Явный выбор его отменяет: иначе выбранный протокол
        // остаётся недоступным до истечения таймера, и объяснить это нечем.
        //
        // Но у уважения к выбору есть предел. Если MASQUE срывается раз за
        // разом, бесконечные попытки превращаются в цикл переподключений, из
        // которого пользователь не выберется. После EXPLICIT_CHOICE_FAILURE_LIMIT
        // срывов подряд уступаем WARP и говорим об этом вслух.
        //
        // Уступка обязательно временная. Счётчик срывов обнуляется только
        // успешным подключением по MASQUE, поэтому без срока действия
        // получалась ловушка: три срыва — и выбранный протокол больше не
        // пробуется никогда, а успеха взяться неоткуда, потому что попыток нет.
        // Снаружи это выглядело как «выбрал MASQUE, а попыток вообще не было».
        val brokenDespiteExplicitChoice = explicit &&
            inputs.failureStreak >= EXPLICIT_CHOICE_FAILURE_LIMIT &&
            inputs.explicitLockoutFresh
        val skipForCooldown = brokenDespiteExplicitChoice ||
            // Мало убрать MASQUE с первого места: оставленный в цепочке, он всё равно
            // сожжёт свои полминуты, просто позже. Раз сеть его не пропускает — в этом
            // цикле не пробуем вовсе.
            autoBrokenByFailures ||
            (
                !explicit &&
                    (inputs.underlyingNetworkMetered || inputs.restrictedMobileNetwork) &&
                    inputs.cooldownActive &&
                    !inputs.hasFreshLastSuccess &&
                    !inputs.hasStrongVerifiedMasque
                )

        // Регистрация в Cloudflare идёт через Opera-прокси и на быстром старте
        // ограничена несколькими секундами. Для явного выбора это неверный
        // компромисс: не уложились в бюджет — значит MASQUE не будет вообще.
        val thoroughRegistration = explicit && !inputs.hasCachedIdentity

        // Быстрый старт отказывается от скана и берёт короткий список anycast-соседей:
        // три порта на одном адресе, и все UDP. Для догадки это разумный размен, для
        // явного выбора — нет. Получалось наоборот: выбранный пользователем MASQUE
        // пробовал меньше вариантов, чем «Авто», и на сети, режущей QUIC по 500/1701/
        // 4500, не имел ни единого шанса — скан нашёл бы 443 и 8443.
        val thoroughCandidateScan = explicit

        return Decision(
            masqueFirst = masqueFirst,
            deferForUserImported = deferForUserImported,
            deferForMessenger = deferForMessenger,
            deferForOrdinaryWifi = deferForOrdinaryWifi,
            skipForCooldown = skipForCooldown,
            brokenDespiteExplicitChoice = brokenDespiteExplicitChoice,
            thoroughRegistration = thoroughRegistration,
            thoroughCandidateScan = thoroughCandidateScan,
        )
    }
}
