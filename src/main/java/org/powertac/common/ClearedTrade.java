/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package org.powertac.common;

import org.joda.time.Instant;
import org.powertac.common.state.Domain;
import org.powertac.common.xml.TimeslotConverter;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamConverter;

/**
 * A clearedTrade instance reports public trade information, i.e. the execution price and quantity.
 *
 * It relates to a single transaction, specifying the timeslot and product. The single clearedTrade
 * instances are aggregated in a collection by the auctioneer before they are sent to other entities/brokers.
 * In the periodic clearing this collection is collection is built after each clearing and includes
 * a clearedTrade instance per tradeable timslot and product.
 *
 * @author Daniel Schnurr
 */
@Domain
@XStreamAlias("trade")
public class ClearedTrade
{
  @XStreamAsAttribute
  private long id = IdGenerator.createId();

  /** underlying timeslot for the trade (e.g. for a future the timeslot when real-world exchanges happen)*/
  @XStreamConverter(TimeslotConverter.class)
  private Timeslot timeslot;

  /** the transactionId is generated during the execution of a trade in market and
   * relates corresponding domain instances that were created or changed during
   * this transaction. Like this the clearedTradeInstance with transactionId=1
   * can be correlated to shout instances with transactionId=1 in ex-post analysis  */
  //@XStreamAsAttribute
  //private long transactionId;

  /** clearing price of the trade */
  @XStreamAsAttribute
  private double executionPrice;

  /** traded quantity in mWh of the specified product */
  @XStreamAsAttribute
  private double executionMWh;

  /** point in time when cleared Trade object was created */
  @XStreamAsAttribute
  private Instant dateExecuted;
  
  public ClearedTrade (Timeslot timeslot, double executionMWh,
                       double executionPrice, Instant dateExecuted)
  {
    super();
    this.timeslot = timeslot;
    this.executionPrice = executionPrice;
    this.executionMWh = executionMWh;
    this.dateExecuted = dateExecuted;
  }

  public long getId ()
  {
    return id;
  }

  public Timeslot getTimeslot ()
  {
    return timeslot;
  }

  public double getExecutionPrice ()
  {
    return executionPrice;
  }

  public double getExecutionMWh ()
  {
    return executionMWh;
  }

  public Instant getDateExecuted ()
  {
    return dateExecuted;
  }
  
  public String toString()
  {
    return "ClearedTrade " + executionMWh + "@" + executionPrice;
  }
}
