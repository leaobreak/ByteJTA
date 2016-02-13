/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytejta;

import java.util.List;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.log4j.Logger;
import org.bytesoft.bytejta.aware.TransactionBeanFactoryAware;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.supports.logger.TransactionLogger;
import org.bytesoft.transaction.xa.TransactionXid;

public class TransactionCoordinator implements RemoteCoordinator, TransactionBeanFactoryAware {
	static final Logger logger = Logger.getLogger(TransactionCoordinator.class.getSimpleName());

	private TransactionBeanFactory beanFactory;

	public String getIdentifier() {
		throw new IllegalStateException();
	}

	public Transaction getTransactionQuietly() {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		return transactionManager.getTransactionQuietly();
	}

	public void start(TransactionContext transactionContext, int flags) throws TransactionException {

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		if (transactionManager.getTransactionQuietly() != null) {
			throw new TransactionException(XAException.XAER_PROTO);
		}

		TransactionXid globalXid = (TransactionXid) transactionContext.getXid();
		Transaction transaction = transactionRepository.getTransaction(globalXid);
		if (transaction == null) {
			transaction = new TransactionImpl(transactionContext);
			transaction.setBeanFactory(this.beanFactory);

			long expired = transactionContext.getExpiredTime();
			long current = System.currentTimeMillis();
			long timeoutMillis = (expired - current) / 1000L;
			transaction.setTransactionTimeout((int) timeoutMillis);

			transactionRepository.putTransaction(transactionContext.getXid(), transaction);
		}

		transactionManager.associateThread(transaction);
		// this.transactionStatistic.fireBeginTransaction(transaction);

	}

	public void end(TransactionContext transactionContext, int flags) throws TransactionException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		transactionManager.desociateThread();
	}

	/** supports resume only, for tcc transaction manager. */
	public void start(Xid xid, int flags) throws XAException {
		if (XAResource.TMRESUME != flags) {
			throw new XAException(XAException.XAER_INVAL);
		}
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		Transaction current = transactionManager.getTransactionQuietly();
		if (current != null) {
			throw new XAException(XAException.XAER_PROTO);
		}

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();

		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();

		Transaction transaction = transactionRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_INVAL);
		}
		transactionManager.associateThread(transaction);
	}

	/** supports suspend only, for tcc transaction manager. */
	public void end(Xid xid, int flags) throws XAException {
		if (XAResource.TMSUSPEND != flags) {
			throw new XAException(XAException.XAER_INVAL);
		}
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		Transaction transaction = transactionManager.getTransactionQuietly();
		if (transaction == null) {
			throw new XAException(XAException.XAER_PROTO);
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid transactionXid = transactionContext.getXid();

		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();

		if (CommonUtils.equals(globalXid, transactionXid) == false) {
			throw new XAException(XAException.XAER_INVAL);
		}
		transactionManager.desociateThread();
	}

	public void commit(Xid xid, boolean onePhase) throws XAException {
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();
		TransactionRepository repository = beanFactory.getTransactionRepository();
		Transaction transaction = repository.getTransaction(globalXid);
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean transactionDone = true;
		try {
			if (onePhase) {
				transaction.commit();
			} else if (transactionContext.isCoordinator()) {
				transaction.commit();
			} else {
				transaction.participantCommit();
			}
		} catch (SecurityException ex) {
			logger.error("Error occurred while committing remote coordinator.", ex);
			transactionDone = false;
			repository.putErrorTransaction(globalXid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (IllegalStateException ex) {
			logger.error("Error occurred while committing remote coordinator.", ex);
			transactionDone = false;
			repository.putErrorTransaction(globalXid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (CommitRequiredException ex) {
			logger.error("Error occurred while committing remote coordinator.", ex);
			transactionDone = false;
			repository.putErrorTransaction(globalXid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (RollbackException ex) {
			logger.error("Error occurred while committing remote coordinator, tx has been rolled back.", ex);

			XAException xaex = new XAException(XAException.XA_HEURRB);
			xaex.initCause(ex);
			throw xaex;
		} catch (HeuristicMixedException ex) {
			logger.error("Error occurred while committing remote coordinator, tx has been completed mixed.", ex);
			transactionDone = false;
			repository.putErrorTransaction(globalXid, transaction);

			XAException xaex = new XAException(XAException.XA_HEURMIX);
			xaex.initCause(ex);
			throw xaex;
		} catch (HeuristicRollbackException ex) {
			logger.error("Error occurred while committing remote coordinator, tx has been rolled back heuristically.", ex);

			XAException xaex = new XAException(XAException.XA_HEURRB);
			xaex.initCause(ex);
			throw xaex;
		} catch (SystemException ex) {
			logger.error("Error occurred while committing remote coordinator.", ex);
			transactionDone = false;
			repository.putErrorTransaction(globalXid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} finally {
			if (transactionDone) {
				repository.removeErrorTransaction(globalXid);
				repository.removeTransaction(globalXid);
			}
		}
	}

	public void forget(Xid xid) throws XAException {
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();
		TransactionRepository transactionRepository = beanFactory.getTransactionRepository();
		Transaction transaction = transactionRepository.getErrorTransaction(globalXid);
		if (transaction == null) {
			return;
		}

		TransactionLogger transactionLogger = beanFactory.getTransactionLogger();
		try {
			TransactionArchive archive = transaction.getTransactionArchive();
			transactionLogger.deleteTransaction(archive);

			transactionRepository.removeErrorTransaction(globalXid);
			transactionRepository.removeTransaction(globalXid);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while forgeting remote coordinator.", rex);
			throw new XAException(XAException.XAER_RMERR);
		}
	}

	public int getTransactionTimeout() throws XAException {
		return 0;
	}

	public boolean isSameRM(XAResource xares) throws XAException {
		throw new XAException(XAException.XAER_RMERR);
	}

	public int prepare(Xid xid) throws XAException {
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();
		TransactionRepository repository = beanFactory.getTransactionRepository();
		Transaction transaction = repository.getTransaction(globalXid);
		try {
			transaction.participantPrepare();
		} catch (CommitRequiredException crex) {
			return XAResource.XA_OK;
		} catch (RollbackRequiredException rrex) {
			throw new XAException(XAException.XAER_RMERR);
		}
		return XAResource.XA_RDONLY;
	}

	public Xid[] recover(int flag) throws XAException {
		TransactionRepository repository = beanFactory.getTransactionRepository();
		List<Transaction> transactionList = repository.getErrorTransactionList();
		TransactionXid[] xidArray = new TransactionXid[transactionList.size()];
		for (int i = 0; i < transactionList.size(); i++) {
			Transaction transaction = transactionList.get(i);
			TransactionContext transactionContext = transaction.getTransactionContext();
			TransactionXid xid = transactionContext.getXid();
			xidArray[i] = xid;
		}
		return xidArray;
	}

	public void rollback(Xid xid) throws XAException {
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();
		TransactionRepository repository = beanFactory.getTransactionRepository();
		Transaction transaction = repository.getTransaction(globalXid);

		boolean transactionDone = true;
		try {
			transaction.rollback();
		} catch (RollbackRequiredException rrex) {
			logger.error("Error occurred while rolling back remote coordinator.", rrex);
			transactionDone = false;
			repository.putErrorTransaction(globalXid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		} catch (SystemException ex) {
			logger.error("Error occurred while rolling back remote coordinator.", ex);
			transactionDone = false;
			repository.putErrorTransaction(globalXid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (RuntimeException rrex) {
			logger.error("Error occurred while rolling back remote coordinator.", rrex);
			transactionDone = false;
			repository.putErrorTransaction(globalXid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		} finally {
			if (transactionDone) {
				repository.removeErrorTransaction(globalXid);
				repository.removeTransaction(globalXid);
			}
		}
	}

	public boolean setTransactionTimeout(int seconds) throws XAException {
		return false;
	}

	public void setBeanFactory(TransactionBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
