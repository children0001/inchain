package org.inchain.msgprocess;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.inchain.Configure;
import org.inchain.core.Coin;
import org.inchain.core.Definition;
import org.inchain.core.Peer;
import org.inchain.core.exception.VerificationException;
import org.inchain.kits.PeerKit;
import org.inchain.message.Block;
import org.inchain.message.Message;
import org.inchain.message.RejectMessage;
import org.inchain.network.NetworkParams;
import org.inchain.service.BlockForkService;
import org.inchain.service.CreditCollectionService;
import org.inchain.store.BlockHeaderStore;
import org.inchain.store.BlockStore;
import org.inchain.store.BlockStoreProvider;
import org.inchain.store.ChainstateStoreProvider;
import org.inchain.store.TransactionStoreProvider;
import org.inchain.transaction.Transaction;
import org.inchain.transaction.business.CreditTransaction;
import org.inchain.utils.ConsensusRewardCalculationUtil;
import org.inchain.validator.TransactionValidator;
import org.inchain.validator.TransactionValidatorResult;
import org.inchain.validator.ValidatorResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 下载区块的消息
 * 接收到新的区块之后，验证该区块是否合法，如果合法则进行收录并转播出去
 * 验证该区块是否合法的流程为：
 * 1、该区块基本的验证（包括区块的时间、大小、交易的合法性，梅克尔树根是否正确）。
 * 2、该区块的广播人是否是合法的委托人。
 * 3、该区块是否衔接最新区块，不允许分叉区块。
 * @author ln
 *
 */
@Service
public class BlockMessageProcess implements MessageProcess {

	private static final Logger log = LoggerFactory.getLogger(BlockMessageProcess.class);

	private static Lock lock = new ReentrantLock();
	
	@Autowired
	private PeerKit peerKit;
	@Autowired
	private NetworkParams network;
	@Autowired
	private CreditCollectionService creditCollectionService;
	@Autowired
	private BlockStoreProvider blockStoreProvider;
	@Autowired
	private TransactionStoreProvider transactionStoreProvider;
	@Autowired
	private ChainstateStoreProvider chainstateStoreProvider;
	@Autowired
	private TransactionValidator transactionValidator;
	@Autowired
	protected BlockForkService blockForkService;
	
	/**
	 * 接收到区块消息，进行区块合法性验证，如果验证通过，则收录，然后转发区块
	 */
	@Override
	public MessageProcessResult process(Message message, Peer peer) {
		
		if(log.isDebugEnabled()) {
			log.debug("down block : {}", message);
		}
		
		lock.lock();
		
		Block block = (Block) message;
		
		try {
			BlockHeaderStore header = blockStoreProvider.getHeader(block.getHash().getBytes());
			if(header != null) {
				//已经存在
				return replyRejectMessage(block);
			}
			
			//验证区块消息的合法性
			if(!verifyBlock(block)) {
				
				blockForkService.addBlockFork(block);
				
				return replyRejectMessage(block);
			}
			
			//验证通过 ，存储区块数据
			try {
				BlockStore blockStore = new BlockStore(network, block);
				blockStoreProvider.saveBlock(blockStore);
			} catch (IOException e) {
				throw new VerificationException(e);
			}
			
			//区块变化监听器
			if(peerKit.getBlockChangedListener() != null) {
				peerKit.getBlockChangedListener().onChanged(block.getHeight(), -1l, block.getHash(), null);
			}
		} catch (Exception e) {
			blockForkService.addBlockFork(block);
			log.error(e.getMessage(), e);
			return replyRejectMessage(block);
		} finally {
			lock.unlock();
		}
		return new MessageProcessResult(block.getHash(), true);
	}

	/*
	 * 验证区块的合法性，如果验证不通过，则抛出验证异常
	 */
	private boolean verifyBlock(Block block) {
		//验证区块签名
		block.verifyScript();
		
		//验证梅克尔树根是否正确
		if(!block.buildMerkleHash().equals(block.getMerkleHash())) {
			throw new VerificationException("block merkle hash error");
		}
		
		//验证交易是否合法
		Coin coinbaseFee = Coin.ZERO; //coinbase 交易包含的金额，主要是手续费
		Coin fee = Coin.ZERO; 		  //手续费
		
		//每个区块只能包含一个coinbase交易，并且只能是第一个
		boolean coinbase = false;
		
		List<Transaction> txs = block.getTxs();
		for (Transaction tx : txs) {
			
			ValidatorResult<TransactionValidatorResult> rs = transactionValidator.valDo(tx, txs);
			
			if(!rs.getResult().isSuccess()) {
				throw new VerificationException(rs.getResult().getMessage());
			}
			//信用累积交易，比较特殊，这里单独验证
			if(tx.getType() == Definition.TYPE_CREDIT) {
				verifyCreditTransaction(tx, txs, block);
			}
			
			//区块的第一个交易必然是coinbase交易，除第一个之外的任何交易都不应是coinbase交易，否则出错
			if(!coinbase) {
				if(tx.getType() != Definition.TYPE_COINBASE) {
					throw new VerificationException("the block first tx is not coinbase tx");
				}
				coinbaseFee = Coin.valueOf(tx.getOutput(0).getValue());
				coinbase = true;
				continue;
			} else if(tx.getType() == Definition.TYPE_COINBASE) {
				throw new VerificationException("the block too much coinbase tx");
			}
			if(rs.getResult().getFee() != null) {
				fee = fee.add(rs.getResult().getFee());
			}
		}
		//验证金额，coinbase交易的费用必须等于交易手续费
		//获取该高度的奖励
		Coin rewardCoin = ConsensusRewardCalculationUtil.calculat(block.getHeight());
		if(!coinbaseFee.equals(fee.add(rewardCoin))) {
			log.warn("the fee error");
			return false;
		}
		//获取区块的最新高度
		BlockHeaderStore bestBlockHeader = blockStoreProvider.getBestBlockHeader();
		//必需衔接
		if(!block.getPreHash().equals(bestBlockHeader.getBlockHeader().getHash()) ||
				block.getHeight() != bestBlockHeader.getBlockHeader().getHeight() + 1) {
			log.warn("block info warn");
			blockForkService.addBlockFork(block);
			return false;
		}
		return true;
	}
	
	/*
	 * 验证信用发放
	 */
	private void verifyCreditTransaction(Transaction tx, List<Transaction> txs, Block block) {
		if(!(tx instanceof CreditTransaction)) {
			throw new VerificationException("错误的交易");
		}
		//信用累计，信用惩罚在 ViolationTransaction 里面 
		CreditTransaction creditTx = (CreditTransaction) tx;
		if(creditTx.getReasonType() == Definition.CREDIT_TYPE_PAY) {
			//验证是否在系统设定的时间内只奖励过一次
			//要能快速的验证，需要良好的设计
			//最有效的方法是以空间换时间，把相关的信息存在到内存里面

			if(creditTx.getCredit() != Configure.CERT_CHANGE_PAY) {
				throw new VerificationException("信用值不正确");
			}
			
			//被奖励人
			byte[] hash160 = creditTx.getOwnerHash160();
			
			//验证凭证是否合法，凭证必须和信用在同一个块
			if(txs == null || creditTx.getReason() == null) {
				throw new VerificationException("凭证不存在");
			}
			Transaction certificateTx = null;
			for (Transaction txTemp : txs) {
				if(txTemp.getHash().equals(creditTx.getReason())) {
					certificateTx = txTemp;
					break;
				}
			}
			if(certificateTx == null) {
				throw new VerificationException("凭证没有找到");
			}
			
			if(certificateTx.getType() != Definition.TYPE_PAY) {
				throw new VerificationException("无效凭证");
			}
			
			//信用发放给正确的人
			byte[] hash160Temp = certificateTx.getInput(0).getFromScriptSig().getPubKeyHash();
			if(!Arrays.equals(hash160Temp, hash160)) {
				throw new VerificationException("信用没有发放给正确的人");
			}
			
			if(!creditCollectionService.verification(creditTx.getReasonType(), hash160, block.getTime())) {
				throw new VerificationException("验证失败，不能发放信用值");
			}
		} else {
			throw new VerificationException("暂时未实现的交易");
		}
	}

	/**
	 * 回复拒绝消息
	 * @param block
	 * @return MessageProcessResult
	 */
	protected MessageProcessResult replyRejectMessage(Block block) {
		RejectMessage replyMessage = new RejectMessage(network, block.getHash());
		return new MessageProcessResult(block.getHash(), false, replyMessage);
	}
}
