/*
 * Copyright (c) 2011 by the original author
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
package org.powertac.common.repo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

//import org.apache.log4j.Logger;
import org.powertac.common.Timeslot;
import org.powertac.common.WeatherForecast;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Repository for WeatherReports. The weather reports are indexed by the 
 * timeslot that they are issued for. This allows them to be quickly accessed
 * via a hashMap.
 * @author Erik Onarheim
 */
@Repository
public class WeatherForecastRepo implements DomainRepo
{
	  //static private Logger log = Logger.getLogger(WeatherForecastRepo.class.getName());

	  // storage
	  private HashMap<Timeslot, WeatherForecast> indexedWeatherForecasts;

	  @Autowired
	  private TimeslotRepo timeslotRepo;
	  
	  /** standard constructor */
	  public WeatherForecastRepo ()
	  {
	    super();
	    indexedWeatherForecasts = new HashMap<Timeslot, WeatherForecast>();
	  }
	  
	  /** 
	   * Adds a WeatherForecast to the repo
	   */
	  public void add (WeatherForecast weather)
	  {
		  indexedWeatherForecasts.put(weather.getCurrentTimeslot(), weather);
	  }
	  
	  /**
	   * Returns the current WeatherForecast
	   */
	  public WeatherForecast currentWeatherForecast () 
	  {
		  // Returns the weather report for the current timeslot
	      return indexedWeatherForecasts.get(timeslotRepo.currentTimeslot()); 
	  }

	  /**
	   * Returns a list of all the issued weather forecast up to the currentTimeslot
	   */
	  public List<WeatherForecast> allWeatherForecasts ()
	  {
		  Timeslot current = timeslotRepo.currentTimeslot();
		  // Some weather forecasts exist in the repo for the future 
		  // but have not been issued for the current timeslot.
		  ArrayList<WeatherForecast> issuedReports = new ArrayList<WeatherForecast>();
		  for ( WeatherForecast w : indexedWeatherForecasts.values()){
			  if(w.getCurrentTimeslot().getStartInstant().isBefore(current.getStartInstant())){
				  issuedReports.add(w);
			  }
		  }

		  issuedReports.add(this.currentWeatherForecast());
		  
		  return (List<WeatherForecast>) issuedReports;
	  }
	  
	  /**
	   * Returns the number of WeatherForecasts that have been successfully created.
	   */
	  public int count ()
	  {
	    return indexedWeatherForecasts.size();
	  }

	  public void recycle ()
	  {
	    
	  }
}
