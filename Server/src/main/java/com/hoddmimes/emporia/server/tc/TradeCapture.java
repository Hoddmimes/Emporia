package com.hoddmimes.emporia.server.tc;

import com.hoddmimes.emporia.server.Database;
import com.hoddmimes.emporia.server.bdx.BdxDistributor;
import com.hoddmimes.emporia.server.me.InternalTrade;

import java.util.Vector;

public class TradeCapture
{
    Vector<InternalTrade> mTrades;
    BdxDistributor mBdx;


    public TradeCapture(Database mDb, BdxDistributor pBdx) {
        mTrades = new Vector<>();
        mBdx = pBdx;
    }

    public void addTrade( InternalTrade pTrade) {
        mTrades.add( pTrade ); // Add trade to the TC cache
    }

}
