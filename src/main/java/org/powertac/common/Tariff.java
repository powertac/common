/*
 * Copyright (c) 2011-2013 by John Collins.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.repo.TariffRepo;
import org.powertac.common.spring.SpringApplicationContext;
import org.powertac.common.state.Domain;
import org.powertac.common.state.StateChange;

/**
 * Entity wrapper for TariffSpecification that supports Tariff evaluation 
 * and billing. Instances of this class are not intended to be serialized.
 * Tariffs are composed of Rates, which may be applicable for limited daily
 * and/or weekly times, and within particular usage tiers. The Tariff
 * transforms the list of Rates into an array, indexed first by tier and
 * second by hour, making it easy to find the correct Rate that applies for
 * a particular accounting event. This will also make it easy to estimate the
 * cost of a multi-Rate Tariff given an expected load/production profile.
 * <p>
 * This is not a serializable type; The server and brokers are responsible
 * for creating and maintaining their own Tariff entities if they have an
 * interest in the transformation of TariffSpecification represented by a
 * Tariff instance.</p>
 * <p>
 * <strong>NOTE:</strong> When creating one of these for the first time, you must
 * call the init() method to initialize the publication date. It does not work
 * to call it inside the constructor for some reason.</p>
 * @author John Collins
 */
@Domain
public class Tariff
{
  static private Logger log = LogManager.getLogger(Tariff.class.getName());

  // ----------- State enumeration --------------

  public enum State
  {
    PENDING, OFFERED, ACTIVE, WITHDRAWN, KILLED
  }

  private TimeService timeService;
  
  private TariffRepo tariffRepo;

  private long specId;

  /** The Tariff spec*/
  private TariffSpecification tariffSpec;

  /** The broker behind this tariff */
  private Broker broker;

  /** Current state of this Tariff */
  private State state = State.PENDING;

  /** ID of Tariff that supersedes this Tariff */
  private Tariff isSupersededBy;

  /** Tracks the realized price for variable-rate tariffs. */
  // default visibility to facilitate testing
  double totalCost = 0.0;
  double totalUsage = 0.0;

  /** Records the date when the Tariff was first offered */
  private Instant offerDate;
  
  /** Tariff expiration date, possibly updated from original spec */
  private Instant expiration;

  /** Maximum future interval over which price can be known */
  //private Duration maxHorizon; 

  /** True if the maps are keyed by hour-in-week rather than hour-in-day */
  private boolean isWeekly = false;
  private boolean analyzed = false;
  
  // local rate id map to support updates of hourly rates
  private HashMap<Long, Rate> rateIdMap;

  // map is an array, indexed by tier-threshold and hour-in-day/week
  private TreeSet< Double > tiers;
  private int tierSign = 1; // -1 for negative tiers
  private Rate[][] rateMap;
  private RegulationRate regulationRate;

  /**
   * Creates a new Tariff from the given TariffSpecification. Note that 
   * the tariff will not be usable until its init() method has been called.  
   */
  public Tariff (TariffSpecification spec)
  {
    tariffSpec = spec;
    specId = spec.getId();
    broker = spec.getBroker();
    expiration = spec.getExpiration();
    rateIdMap = new HashMap<Long, Rate>();
    for (Rate r: spec.getRates()) {
      rateIdMap.put(r.getId(), r);
    }
    for (RegulationRate r: spec.getRegulationRates()) {
      if (null != regulationRate) {
        log.warn("Multiple regulation rates on tariff " + getId());
      }
      else {
        regulationRate = r;
      }
    }
    tiers = new TreeSet<Double>();
  }

  /**
   * Initializes tariff by building the rate map. Must be called before
   * usage charges can be computed. This is not in the constructor because
   * of testability problems.
   */
  public boolean init ()
  {
    if (null == timeService)
      timeService = (TimeService)SpringApplicationContext.getBean("timeService");
    if (null == tariffRepo)
      tariffRepo= (TariffRepo)SpringApplicationContext.getBean("tariffRepo");
    offerDate = timeService.getCurrentTime();

    // make sure this tariff is valid before adding it to the repo
    analyze();
    if (!isCovered())
      return false;

    // it's good.
    tariffRepo.addTariff(this);
    if (tariffSpec.getSupersedes() != null) {
      for (long supId : tariffSpec.getSupersedes()) {
        Tariff supersededTariff = tariffRepo.findTariffById(supId);
        if (supersededTariff == null)
          log.error("Superseded tariff " + supId + " not found");
        else if (!(supersededTariff.getPowerType() == this.getPowerType())
                 && !(supersededTariff.getPowerType().canUse(this.getPowerType())))
          log.error("Tariff " + supId
                    + ", powerType=" + supersededTariff.getPowerType()
                    + " cannot be superseded by tariff " + this.getId() 
                    + ", powerType=" + this.getPowerType());
        else
          supersededTariff.isSupersededBy = this;
      }
    }
    return true;
  }
  
  public TariffSpecification getTariffSpecification ()
  {
    return tariffSpec;
  }
  
  public long getSpecId ()
  {
    return specId;
  }

  /**
   * make id a synonym for specId
   */
  public long getId ()
  {
    return specId;
  }
  
  // set id for testing and log reconstruction
  void setId (long id)
  {
    specId = id;
  }
  
  /**
   * Adds a new HourlyCharge to its Rate. Returns true just
   * in case the operation was successful.
   */
  @StateChange
  public boolean addHourlyCharge (HourlyCharge newCharge, long rateId)
  {
    Rate theRate = rateIdMap.get(rateId);
    if (theRate == null) {
      log.error("addHourlyCharge - no rate " + rateId);
      return false;
    }
    return theRate.addHourlyCharge(newCharge);
  }

  /** 
   * Returns the actual realized price, or 0.0 if information unavailable.
   * This value is negative for consumption tariffs, because it indicates
   * that the customer has paid the broker.  
   */
  public double getRealizedPrice ()
  {
    if (totalUsage == 0.0) {
      return 0.0;
    }
    else {
      double sign = (tariffSpec.getPowerType().isProduction()) ? -1.0 : 1.0;
      return sign * totalCost / totalUsage;
    }
  }

  /** 
   * Delegation for TariffSpecification.minDuration 
   */
  public long getMinDuration ()
  {
    return tariffSpec.getMinDuration();
  }

  /** Type of power covered by this tariff */
  public PowerType getPowerType ()
  {
    return tariffSpec.getPowerType();
  }

  /** 
   * One-time payment for subscribing to tariff, negative for payment
   * from customer, positive for payment to customer. 
   */
  public double getSignupPayment ()
  {
    return tariffSpec.getSignupPayment();
  }

  /** 
   * Payment from customer to broker for canceling subscription before
   * minDuration has elapsed. This is typically a negative value. 
   */
  public double getEarlyWithdrawPayment ()
  {
    return tariffSpec.getEarlyWithdrawPayment();
  }

  /** Flat payment per period for two-part tariffs, typically negative. */
  public double getPeriodicPayment ()
  {
    return tariffSpec.getPeriodicPayment();
  }

  /**
   * Returns the maximum interruptible quantity in kwh for this tariff in 
   * the current timeslot, for the specified proposed and cumulative usage.
   */
  public double getMaxUpRegulation (double kwh, double cumulativeUsage)
  {
    // first, make sure this is an interruptible power type
    // TODO - what about storage types?
    if (! tariffSpec.getPowerType().isInterruptible())
      return 0.0;
    
    // Next, we need to explore the rate structure. This starts with
    // the time index
    int di = getTimeIndex(timeService.getCurrentTime());
        
    // Then work out the tier index. Keep in mind that the kwh value could
    // cross a tier boundary
    if (tiers.size() == 1) {
      Rate rate = rateMap[0][di];
      return kwh * rate.getMaxCurtailment();
    }
    else {
      double result = 0.0;
      List<RateKwh> rkList = getRateKwhList(di, kwh, cumulativeUsage);
      // TODO this calculation is not quite right unless curtailment is only
      // on top tier.
      for (RateKwh rk : rkList) {
        result += rk.kwh * rk.rate.getMaxCurtailment();
      }
      return result;
    }

  }

  /** 
   * Returns the usage charge for a single customer in the current timeslot. 
   * If the kwh parameter is given as +1.0, you get the per-kwh value for 
   * energy consumption, which is typically a negative value. If you supply
   * a non-zero value for cumulativeUsage, then the charge will be affected by the
   * rate tier structure in accordance with the new cumulative usage.
   * <p>
   * If the recordUsage parameter is true, then the usage and price will be
   * recorded to update the realizedPrice.</p>
   */
  public double getUsageCharge (double kwh, double cumulativeUsage,
                                boolean recordUsage)
  {
    double amt =
      getUsageCharge(timeService.getCurrentTime(), kwh, cumulativeUsage);
    if (recordUsage) {
      totalUsage += kwh;
      totalCost += amt;
    }
    return amt;
  }

  /**
   * Returns the usage charge for regulation usage/or production. If this
   * tariff has a RegulationRate, then that will determine the charge;
   * otherwise the call will be delegated to getUsageCharge(). 
   * Regulation usage does not contribute to cumulative usage, since it's
   * assumed to balance out over time.
   * Note that
   * negative values for kwh represent up-regulation, while positive values
   * represent down-regulation.
   */
  public double getRegulationCharge (double kwh, double cumulativeUsage,
                                     boolean recordUsage)
  {
    if (null == regulationRate) {
      return getUsageCharge(-kwh, cumulativeUsage, recordUsage);
    }
    else {
      if (kwh < 0.0) {
        // up-regulation
        return kwh * regulationRate.getUpRegulationPayment();
      }
      else {
        // down-regulation
        return -kwh * regulationRate.getDownRegulationPayment();
      }
    }
  }

  /** 
   * Returns the usage charge for a single customer using an amount of 
   * energy at some time in the past or future. The return value is typically
   * opposite in sign to the kwh parameter.
   * If the requested time is farther in the future 
   * than maxHorizon, then the result will likely be a default value, which 
   * may not be useful. The cumulativeUsage parameter sets the base for
   * probing the rate tier structure. Do not use this method for billing,
   * because it does not update the realized-price data.
   */
  public double getUsageCharge (Instant when,
                                double kwh, double cumulativeUsage)
  {
    return getUsageCharge(when, kwh, cumulativeUsage, null);
  }

  /**
   * Returns a risk-adjusted usage charge, with prices for variable rates
   * adjusted by the given TariffEvaluationHelper.
   */
  public double getUsageCharge (Instant when,
                                double kwh, double cumulativeUsage,
                                TariffEvaluationHelper helper)
  {
    double result = 0.0;
    // first, get the time index
    int di = getTimeIndex(when);
    
    // next, adjust the sign of the result. Production is kwh<0, and rate>0.
    // Consumption is kwh>0 and rate<0. If we multiply them, we get the same
    // sign. So this is the adjustment:
    double sign = (tariffSpec.getPowerType().isProduction()) ? -1.0 : 1.0;
    //double sign = Math.signum(kwh);

    // Then work out the tier index. Keep in mind that the kwh value could
    // cross a tier boundary
    if (tiers == null || tiers.size() < 1) {
      log.error("uninitialized tariff " + getId());
      return 0.0;
    }
    else {
      // Find the regulation adjustment, if any
      if (tiers.size() == 1) {
        Rate rate = findRate(0, di);
        double perKWh =
          applyRegulationAdjustment(rate.getValue(when, helper), kwh, helper);
        result = kwh * perKWh;
      }
      else {
        List<RateKwh> rkList = getRateKwhList(di, kwh, cumulativeUsage);
        for (RateKwh rk: rkList) {
          double perKWh =
            applyRegulationAdjustment(rk.rate.getValue(when, helper), kwh,
                                      helper);
          result += rk.kwh * perKWh;
        }
      }
    }
    return sign * result;
  }

  // Adjusts hourly rate for estimated regulation payments.
  private double applyRegulationAdjustment (double value, double kwh,
                                            TariffEvaluationHelper helper)
  {
    if (null == helper || kwh == 0.0 || !this.hasRegulationRate())
      return value;
    double p1 =
      getRegulationCharge(helper.getExpectedCurtailment() / kwh, 0.0, false);
    double p2 =
      getRegulationCharge(helper.getExpectedDischarge() / kwh, 0.0, false);
    double p3 =
      getRegulationCharge(helper.getExpectedDownRegulation() / kwh, 0.0, false);
    double result =
      (value * (1.0 - (helper.getExpectedDischarge()
                       + helper.getExpectedDownRegulation()) / kwh)
       - p1 + p2 + p3);
//    log.info("tariff " + getId() + " regulation adj (v,r,dis,dwn,p1,p2,p3): ("
//             + value + "," + result
//             + "," + helper.getExpectedDischarge() / kwh
//             + "," + helper.getExpectedDownRegulation() / kwh
//             + "," + p1 + "," + p2 + "," + p3 + ")");
    return result;
  }

  private int getTimeIndex (Instant when)
  {
    DateTime dt = new DateTime(when, DateTimeZone.UTC);
    int di = dt.getHourOfDay();
    if (isWeekly)
      di += 24 * (dt.getDayOfWeek() - 1);
    return di;
  }

  public Instant getExpiration ()
  {
    return expiration;
  }

  /**
   * True just in case the current time is past the expiration date
   * of this Tariff.
   */
  public boolean isExpired ()
  {
    if (getExpiration() == null) {
      return false;
    }
    else {
      return !(timeService.getCurrentTime().isBefore(getExpiration()));
    }
  }
  
  @StateChange
  public void setExpiration (Instant newDate)
  {
    expiration = newDate;
  }

  public Instant getOfferDate ()
  {
    return offerDate;
  }
  
  /**
   * True just in case the set of Rates cover all the possible hour
   * and tier slots. If false, then there is some combination of hour
   * and tier for which no Rate is specified.
   */
  public boolean isCovered ()
  {
    for (int tier = 0; tier < tiers.size(); tier++) {
      for (int hour = 0; hour < (isWeekly? 24 * 7: 24); hour++) {
        //def cell = rateMap[tier][hour]
        //println "cell: ${cell}"
        //if (cell == null) {
        //  return false
        //}
        if (rateMap[tier][hour] == null) {
          return false;
        }
      }
    }
    return true;
  }

  public TariffSpecification getTariffSpec ()
  {
    return tariffSpec;
  }

  public Broker getBroker ()
  {
    return broker;
  }

  public State getState ()
  {
    return state;
  }
  
  /**
   * Updates the state of this tariff.
   */
  @StateChange
  public void setState (State newState)
  {
    state = newState;
  }
  
  /**
   * True just in case this tariff is OFFERED or ACTIVE
   */
  public boolean isActive ()
  {
    return (State.OFFERED == state || State.ACTIVE == state); 
  }

  public Tariff getIsSupersededBy ()
  {
    return isSupersededBy;
  }

  public double getTotalCost ()
  {
    return totalCost;
  }

  public double getTotalUsage ()
  {
    return totalUsage;
  }

  public boolean isWeekly ()
  {
    return isWeekly;
  }

  /**
   * True just in case this tariff has been revoked.
   */
  public boolean isRevoked ()
  {
    return state == State.KILLED;
  }
  
  /**
   * True just in case this tariff can accept new subscriptions
   */
  public boolean isSubscribable ()
  {
    return isActive() && !isExpired() && !isRevoked();
  }
  
  /**
   * True just in case this tariff has at least one Time-of-Use rate
   */
  public boolean isTimeOfUse ()
  {
    for (Rate rate : this.getTariffSpec().getRates())
    {
      if (rate.isTimeOfUse())
        return true;
    }
    return false;
  }
  
  /**
   * True just in case this tariff has at least one tiered rate
   */
  public boolean isTiered ()
  {
    for (Rate rate : this.getTariffSpec().getRates()) {
      if (rate.getTierThreshold() != 0.0)
        return true;
    }
    return false;
  }
  
  /**
   * True just in case this tariff has at least one dynamic rate
   */
  public boolean isVariableRate ()
  {
    for (Rate rate : this.getTariffSpec().getRates()) {
      if (!rate.isFixed())
        return true;
    }
    return false;
  }
  
  /**
   * True just in case this tariff could result in curtailment
   */
  public boolean isInterruptible ()
  {
    if (this.getPowerType().isInterruptible()) {
      for (Rate rate : this.getTariffSpec().getRates()) {
        if (rate.getMaxCurtailment() != 0.0)
          return true;
      }
    }
    return false;
  }

  /**
   * Wrapper for TariffSpecification.hasRegulationRate()
   */
  public boolean hasRegulationRate ()
  {
    return this.getTariffSpec().hasRegulationRate();
  }

  /**
   * Processes the tariffSpec, extracting tiers and rates, building a map. We start by
   * imposing a priority order on rate constraints, as follows:
   * <ol>
   *   <li>tierThreshold &gt; 0</li>
   *   <li>weeklyStart &gt; 0</li>
   *   <li>dailyStart &gt; 0</li>
   *   <li>no constraint</li>
   * </ol>
   * We sort the set of rates on these criteria, then populate an array of size
   * [number of tiers][number of hours] where number of hours is either 24 or
   * 168 depending on whether there are any weekly constraints. 
   */
  private void analyze ()
  {
    // Start by computing tier indices, and array width
    HashMap<Double, Integer> tierIndexMap = new HashMap<Double, Integer>();
    if (tariffSpec.getPowerType().isProduction())
      tierSign = -1; // tiers for production tariffs are negative
    tiers.add(0.0 * tierSign);
    int weekMultiplier = 1;
    for (Rate rate : tariffSpec.getRates()) {
      rate.setTimeService(timeService);
      if (rate.getWeeklyBegin() >= 0) {
        isWeekly = true;
        weekMultiplier = 7;
      }
      if (rate.getTierThreshold() * tierSign > 0.0) {
        tiers.add(rate.getTierThreshold() * tierSign);
      }
    }
    log.info("tariff " + specId + ", tiers: " + tiers);

    // Next, fill in the tierIndexMap, which maps tier thresholds to
    // array indices. Remember that there's always a 0.0 tier.
    int tidx = 0;
    for (double threshold : tiers) {
      tierIndexMap.put(threshold, tidx++);
    }

    // Now we can compute the sort keys. Note that the lowest-priority
    // rates will sort first.
    TreeMap<Integer, Rate> annotatedRates = new TreeMap<Integer, Rate>();
    for (Rate rate : tariffSpec.getRates()) {
      int value = 0;
      if (rate.getDailyBegin() >= 0) {
        value = rate.getDailyBegin();
      }
      if (rate.getWeeklyBegin() >= 0) {
        // The first day is 1, otherwise we would have to add 1 here
        value += rate.getWeeklyBegin() * 24;
      }
      if (rate.getTierThreshold() * tierSign > 0.0) {
        // is this correct? Should the 7 apply only for a weekly rate?
        value += tierIndexMap.get(rate.getTierThreshold() * tierSign) * 24 * weekMultiplier;
      }
      log.debug("inserting " + value + ", " + rate.getId());
      annotatedRates.put(value, rate);
    }

    // Next, we create the rateMap
    rateMap = new Rate[tierIndexMap.size()][weekMultiplier * 24];

    // Finally, we step through the sorted Rates and fill in the
    // array. For each Rate, we add it to the array everywhere it
    // applies, even if we are overwriting other Rates that have
    // already been entered.
    for (Map.Entry<Integer, Rate> entry : annotatedRates.entrySet()) {
      Rate rate = entry.getValue();
      int ti = tierIndexMap.get(rate.getTierThreshold() * tierSign);
      int day1 = 0;
      int dayn = 0;
      if (isWeekly) {
        // get first and last applicable day
        if (rate.getWeeklyBegin() >= 0) {
          day1 = rate.getWeeklyBegin() - 1; // days start at 1
          dayn = rate.getWeeklyBegin() - 1;
        }
        else {
          dayn = 6; // no days specified for weekly rate
        }
        if (rate.getWeeklyEnd() >= 0) {
          dayn = rate.getWeeklyEnd() - 1;
        }
      }
      int hr1 = 0;
      int hrn = 23;
      if (rate.getDailyBegin() >= 0) {
        hr1 = rate.getDailyBegin();
        hrn = rate.getDailyEnd();
      }
      log.debug("day1=" + day1 + ", dayn=" + dayn + ", hr1=" + hr1 + ", hrn=" + hrn);
      // now we can fill in the array
      for (int day = (dayn < day1? 0 : day1); day <= dayn; day++) {
        // handle daily wrap-arounds
        for (int hour = (hrn < hr1? 0 : hr1); hour <= hrn; hour++) {
          rateMap[ti][hour + day * 24] = rate;
        }
        if (hrn < hr1) {
          for (int hour = hr1; hour <= 23; hour++) {
            rateMap[ti][hour + (day * 24)] = rate;
          }
        }
      }
      // handle weekly wrap-arounds
      if (dayn < day1) {
        for (int day = day1; day <= 6; day++) {
          // handle daily wrap-arounds
          for (int hour = (hrn < hr1? 0 : hr1); hour <= hrn; hour++) {
            rateMap[ti][hour + day * 24] = rate;
          }
          if (hrn < hr1) {
            for (int hour = hr1; hour <= 23; hour++) {
              rateMap[ti][hour + (day * 24)] = rate;
            }
          }
        }
      }
    }
    analyzed = true;
  }

  private List<RateKwh> getRateKwhList (int timeIndex,
                                        double kwh, 
                                        double cumulativeUsage)
  {
    List<RateKwh> result = new ArrayList<RateKwh>();
    double remainingAmount = kwh * tierSign;
    double accumulatedAmount = cumulativeUsage * tierSign;
    ArrayList<Double> tierList = new ArrayList<Double>(tiers);
    int ti = 0; // tier index
    while (remainingAmount > 0.0) {
      if (tierList.size() > ti + 1) {
        // still tiers remaining
        if (accumulatedAmount >= tierList.get(ti+1)) {
          log.debug("accumulatedAmount " + accumulatedAmount
                    + " above threshold " + (ti+1) + ":" + (tierList.get(ti+1)));
          ti += 1;
        }
        else if (remainingAmount + accumulatedAmount > tierList.get(ti+1)) {
          double amt = tierList.get(ti+1) - accumulatedAmount;
          log.debug("split off " + amt + " below " + tierList.get(ti+1));
          //result += amt * rateValue(ti++, timeIndex, when);
          result.add(new RateKwh(rateMap[ti++][timeIndex], amt * tierSign));
          remainingAmount -= amt;
          accumulatedAmount += amt;
        }
        else {
          // it all fits in the current tier
          log.debug("amount " + remainingAmount + " fits in tier " + ti);
          //result += remainingAmount * rateValue(ti, timeIndex, when);
          result.add(new RateKwh(rateMap[ti][timeIndex], remainingAmount * tierSign));
          remainingAmount = 0.0;
        }
      }
      else {
        // last tier
        log.debug("remainder " + remainingAmount + " fits in top tier");
        //result += remainingAmount * rateValue(ti, timeIndex, when);
        result.add(new RateKwh(rateMap[ti][timeIndex], remainingAmount * tierSign));
        remainingAmount = 0.0;
      }
    }
    return result;
  }

  private Rate findRate (int tierIndex, int timeIndex)
  {
    Rate rate = rateMap[tierIndex][timeIndex];
    if (rate == null) {
      log.error("could not find rate for tier " + tierIndex + ", ti " + timeIndex);
    }
    return rate;
  }

  /**
   * Returns the analyzed flag.
   */
  public boolean isAnalyzed ()
  {
    return analyzed;
  }
  
  /**
   * Holder for a {Rate, kwh} pair. A list of these can be used to represent
   * the ordered set of rates and quantities needed to determine price or
   * curtailable energy at a particular point in time for a tiered-rate tariff.
   */
  class RateKwh
  {
    Rate rate;
    double kwh;
    
    RateKwh (Rate rate, double kwh)
    {
      super();
      this.rate = rate;
      this.kwh = kwh;
    }
  }
}
