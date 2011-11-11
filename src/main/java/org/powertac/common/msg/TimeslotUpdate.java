/*
 * Copyright (c) 2011 by the original author or authors.
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
package org.powertac.common.msg;

import java.util.ArrayList;
import java.util.List;

import org.joda.time.Instant;
import org.powertac.common.IdGenerator;
import org.powertac.common.Timeslot;
import org.powertac.common.state.Domain;

import com.thoughtworks.xstream.annotations.*;

/**
 * Message type that communicates to brokers the set of timeslots that are
 * "open" for trading. These are the timeslots that will be considered in the
 * next market clearing. Shouts for other timeslots will be silently
 * discarded.
 * @author John Collins
 */
@Domain
@XStreamAlias("timeslot-update")
public class TimeslotUpdate 
{
  @XStreamAsAttribute
  private long id = IdGenerator.createId();

  @XStreamImplicit(itemFieldName="timeslot")
  private ArrayList<Timeslot> enabled;
  
  @XStreamAsAttribute
  private Instant postedTime;

  public TimeslotUpdate (Instant postedTime, List<Timeslot> enabled)
  {
    super();
    this.postedTime = postedTime;
    this.enabled = new ArrayList<Timeslot>(enabled);
  }

  public long getId ()
  {
    return id;
  }

  public Instant getPostedTime ()
  {
    return postedTime;
  }

  /**
   * Returns the list of timeslots that are enabled for the coming
   */
  public ArrayList<Timeslot> getEnabled ()
  {
    return enabled;
  }
  
  /**
   * Returns the number of enabled timeslots.
   */
  public int size ()
  {
    return enabled.size();
  }
}
