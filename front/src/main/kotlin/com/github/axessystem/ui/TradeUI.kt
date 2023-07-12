package com.github.axessystem.ui

import com.github.axescode.util.Colors
import com.github.axescode.util.Items.*
import com.github.axescode.util.Sounds
import com.github.axessystem.info
import com.github.axessystem.`object`.trade.Decision
import com.github.axessystem.`object`.trade.TradeData
import com.github.axessystem.`object`.trade.TradeState
import com.github.axessystem.`object`.trade.Trader
import com.github.axessystem.pluginScope
import com.github.axessystem.util.text
import com.github.axessystem.util.texts
import com.github.mckd.ui.UITemplate
import com.github.mckd.ui.UITemplateImpl
import com.github.mckd.ui.UITemplates
import dev.lone.itemsadder.api.FontImages.FontImageWrapper
import io.github.monun.invfx.InvFX
import io.github.monun.invfx.frame.InvFrame
import kotlinx.coroutines.*
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

class TradeUI(
    private val tradeData: TradeData,
    private val viewer: Trader
) {
    companion object {
        private val infoIcon: ItemStack = getCustomItem(Material.PAPER, text("도움말").decoration(TextDecoration.BOLD, true), 10002) { meta ->
            meta.lore(texts("", "   - SHIFT + 클릭 : 세트 단위로 등록 / 회수", "   - 일반 클릭 : 1개 단위로 등록 / 회수"))
        }

        private val cancelIcon: ItemStack = getCustomItem(Material.PAPER, text("나가기").color(Colors.red).decoration(TextDecoration.BOLD, true), 10003) { meta ->
            meta.lore(texts("", "거래를 종료합니다."))
        }

        private val confirmIcon: ItemStack = getCustomItem(Material.PAPER, text("아이템 확정").decoration(TextDecoration.BOLD, true), 10004) { meta ->
            meta.lore(texts(text(""), text("거래 품목을 확정합니다."), text("※이후 품목을 바꿀 수 없습니다.").color(Colors.red).decoration(TextDecoration.BOLD, true)))
        }

        private val tradeIcon: ItemStack = getCustomItem(Material.PAPER, text("거래 완료").decoration(TextDecoration.BOLD, true), 10004) { meta ->
            meta.lore(texts("", "거래를 완료합니다."))
        }
    }

    private val acceptorHead: ItemStack = item(Material.PLAYER_HEAD) { meta ->
        meta as SkullMeta
        meta.owningPlayer = tradeData.acceptor.player
        meta.displayName(text(tradeData.acceptor.player.name).color(Colors.gold))
    }

    private val requesterHead: ItemStack = item(Material.PLAYER_HEAD) { meta ->
        meta as SkullMeta
        meta.owningPlayer = tradeData.requester.player
        meta.displayName(text(tradeData.requester.player.name).color(Colors.gold))
    }

    //거레 취소 시 아이템 돌려주는 롤백용 서브루틴
    private val lazyRollback: Job = pluginScope.launch(start = CoroutineStart.LAZY) {
        tradeData.sendMessageAll("등록된 아이템을 회수합니다.")
        addItem(tradeData.acceptor.player, *tradeData.acceptor.getItems().toTypedArray())
        addItem(tradeData.requester.player, *tradeData.requester.getItems().toTypedArray())
    }

    //저장 시 서브루틴
    private val lazySave: Job = pluginScope.launch(start = CoroutineStart.LAZY) {
        tradeData.sendMessageAll("거래 기록 저장 중...")
        tradeData.saveData()

        tradeData.sendMessageAll("거래 진행 중...")
        addItem(tradeData.requester.player, *tradeData.acceptor.getItems().toTypedArray())
        addItem(tradeData.acceptor.player, *tradeData.requester.getItems().toTypedArray())

        tradeData.sendMessageAll("거래 완료!")
    }

    // 거래 종료 전까지 UI 띄우는 루틴
    private val lazyReOpen: Deferred<*> = pluginScope.async(start = CoroutineStart.LAZY) {
        delay(10L)
        viewer.sendMessage("거래가 끝나기 전까지 창을 닫을 수 없습니다. 거래를 종료하시려면 X 버튼을 클릭해주세요.")
        ui.openUI(viewer.player)
    }

    private val ui: UITemplate = UITemplates.createUI(6) { ui ->
        ui.setSlot(0, 0) { it.item = infoIcon }
        ui.setSlot(2, 0) { it.item = acceptorHead }
        ui.setSlot(6, 0) { it.item = requesterHead }
        ui.setSlot(8, 0) {
            it.item = cancelIcon
            it.setOnClick {
                viewer.player.playSound(Sounds.click)
                tradeData.denyTrade()
            }
        }

        // READY로 상태전환
        if(viewer.decision == Decision.NOT_READY && !viewer.tradeItems.isEmpty) {
            ui.setSlot(if(tradeData.acceptor === viewer) 2 else 6, 4) {
                it.item = confirmIcon
                it.setOnClick {
                    viewer.decision = Decision.READY
                    viewer.player.playSound(Sounds.click)
                }
            }
        }

        //CONFIRM으로 상태전환
        if(tradeData.isAllReady) {
            ui.setSlot(4, 4) {
                it.item = tradeIcon
                it.setOnClick {
                    viewer.player.playSound(Sounds.click)
                    viewer.decision = Decision.CONFIRM

                    // 양쪽 모두 승낙시 거래 진행
                    if(tradeData.isAllConfirmed) {
                        tradeData.successTrade()
                        tradeData.closeAll()
                        return@setOnClick
                    }

                    ui.setSlot(if(tradeData.acceptor === viewer) 2 else 6, 4) { slot ->
                        slot.item = ItemStack(Material.GREEN_WOOL)
                    }
                }
            }
        }

        //아이템 등록
        ui.setOnClickBottom { e ->
            if(viewer.decision == Decision.NOT_READY || isNullOrAir(e.currentItem)) return@setOnClickBottom
            viewer.player.playSound(Sounds.click)
            viewer.registerItem(e.currentItem!!, e.isShiftClick)
        }

        ui.setOnPlayerClose { if(tradeData.tradeState == TradeState.PROCEED) lazyReOpen.start() }
        ui.setOnElseClose {
            viewer.sendMessage("거래가 중지되었습니다.")
            if(tradeData.acceptor !== viewer) tradeData.acceptor.sendMessage("상대가 거래를 종료하였습니다.")
            else tradeData.requester.sendMessage("상대가 거래를 종료하였습니다.")
            lazyRollback.start()
        }
        ui.setOnPluginClose {
            when(tradeData.tradeState) {
                TradeState.PROCEED -> lazyReOpen.start()
                TradeState.DENIED -> {
                    viewer.sendMessage("거래가 중지되었습니다.")
                    if(tradeData.acceptor !== viewer) tradeData.acceptor.sendMessage("상대가 거래를 종료하였습니다.")
                    else tradeData.requester.sendMessage("상대가 거래를 종료하였습니다.")
                    lazyRollback.start()
                }
                TradeState.SUCCESS -> lazySave.start()
            }
        }
    }

    fun getUI(): UITemplate = ui.clone().apply {
        for((i, item) in tradeData.acceptor.getItems().withIndex()) {
            val x = i % 3
            val y = i / 3

            setSlot(x+1, y+1) {
                it.item = item
                if(tradeData.acceptor !== viewer || viewer.decision != Decision.NOT_READY) return@setSlot

                it.setOnClick { e ->
                    if(isNullOrAir(e.currentItem)) return@setOnClick
                    viewer.player.playSound(Sounds.click)
                    viewer.unregisterItem(x, y, e.currentItem!!, e.isShiftClick)
                }
            }
        }

        for((i, item) in tradeData.acceptor.getItems().withIndex()) {
            val x = i % 3
            val y = i / 3

            setSlot(x+5, y+1) {
                it.item = item
                if(tradeData.requester !== viewer || viewer.decision != Decision.NOT_READY) return@setSlot

                it.setOnClick { e ->
                    if(isNullOrAir(e.currentItem)) return@setOnClick
                    viewer.player.playSound(Sounds.click)
                    viewer.unregisterItem(x, y, e.currentItem!!, e.isShiftClick)
                }
            }
        }
    }

    //메인 프레임
//    fun getFrame(): InvFrame {
//        return InvFX.frame(6, text("거래").decoration(TextDecoration.BOLD, true)) {
//            slot(0, 0) {item = infoIcon}
//            slot(2, 0) {item = acceptorHead}
//            slot(6, 0) {item = requesterHead}
//            slot(8, 0) {
//                item = cancelIcon
//                onClick {
//                    viewer.player.playSound(Sounds.click)
//                    tradeData.deny()
//                }
//            }
//            if(!viewer.isConfirmed && !viewer.tradeItems.isEmpty) {
//                slot(if(tradeData.acceptor === viewer) 2 else 6, 4) {
//                    item = confirmIcon
//                    onClick {
//                        viewer.confirm()
//                        viewer.player.playSound(Sounds.click)
//                        tradeData.openAll()
//                    }
//                }
//            }
//
//            if(tradeData.acceptor.isConfirmed && tradeData.requester.isConfirmed) {
//                slot(4, 4) {
//                    item = tradeIcon
//                    onClick {
//                        viewer.player.playSound(Sounds.click)
//                        tradeData.success()
//                    }
//                }
//            }
//
//            list(1, 1, 3, 3, true, {tradeData.acceptor.getItems()}) {
//                transform {it}
//                if(tradeData.acceptor === viewer && !viewer.isConfirmed) {
//                    onClickItem { x, y, _, event ->
//                        if(isNullOrAir(event.currentItem)) return@onClickItem
//                        viewer.player.playSound(Sounds.click)
//                        viewer.unregisterItem(x, y, event.currentItem!!, event.isShiftClick)
//                        tradeData.openAll()
//                    }
//                }
//            }
//
//            list(5, 1, 7, 3, true, {tradeData.requester.getItems()}) {
//                transform {it}
//                if(tradeData.acceptor !== viewer && !viewer.isConfirmed) {
//                    onClickItem { x, y, _, event ->
//                        if(isNullOrAir(event.currentItem)) return@onClickItem
//                        viewer.player.playSound(Sounds.click)
//                        viewer.unregisterItem(x, y, event.currentItem!!, event.isShiftClick)
//                        tradeData.openAll()
//                    }
//                }
//
//            }
//
//            onClickBottom { e ->
//                if(viewer.isConfirmed || isNullOrAir(e.currentItem)) return@onClickBottom
//
//                viewer.player.playSound(Sounds.click)
//                viewer.registerItem(e.currentItem!!, e.isShiftClick)
//                tradeData.openAll()
//            }
//
//            onClose { e ->
//                info(tradeData.tradeState.name)
//                info(e.reason.name)
//                when(tradeData.tradeState) {
//                    TradeState.PROCEED -> {
//                        when(e.reason) {
//                            InventoryCloseEvent.Reason.PLAYER -> {
//                                tradeData.end()
//                                viewer.sendMessage("거래가 중지되었습니다.")
//                                if(tradeData.acceptor !== viewer) {
//                                    tradeData.acceptor.sendMessage("상대가 거래를 종료하였습니다.")
//                                    tradeData.acceptor.closeUI()
//                                }
//                                else {
//                                    tradeData.requester.sendMessage("상대가 거래를 종료하였습니다.")
//                                    tradeData.requester.closeUI()
//                                }
//                            }
//                            InventoryCloseEvent.Reason.OPEN_NEW -> return@onClose
//                            else -> {
//                                tradeData.end()
//                                tradeData.sendMessageAll("오류가 발생하여 거래가 중지되었습니다.")
//                                if(tradeData.acceptor !== viewer) tradeData.acceptor.closeUI()
//                                else tradeData.requester.closeUI()
//                            }
//                        }
//                    }
//                    TradeState.DENIED -> {
//                        tradeData.end()
//                        lazyRollback.start()
//                    }
//                    TradeState.SUCCESS -> {
//                        tradeData.end()
//                        lazySave.start()
//                    }
//
//                    TradeState.END -> return@onClose
//                }
//            }
//
//        }
//    }
}

