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

package org.powertac.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.powertac.common.msg.CustomerBootstrapData;
import org.powertac.common.msg.SimEnd;
import org.powertac.common.msg.SimPause;
import org.powertac.common.msg.SimResume;
import org.powertac.common.msg.SimStart;
import org.powertac.common.spring.SpringApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.util.SystemPropertyUtils;

import com.thoughtworks.xstream.XStream;

@SuppressWarnings("restriction")
@Service
public class XMLMessageConverter 
{
  private static final Log log = LogFactory.getLog(XMLMessageConverter.class);
  
  @SuppressWarnings("rawtypes")
  private Class[] commandClasses = { CustomerBootstrapData.class, SimEnd.class,
      SimStart.class, SimPause.class, SimResume.class };

  private XStream xstream;
  
  // inject context here so that it would be initialized before this class
  // @PostConstruct method get called and use the singleton.
  @SuppressWarnings("unused")
  @Autowired
  private SpringApplicationContext context;

  @SuppressWarnings("rawtypes")
  @PostConstruct
  public void afterPropertiesSet() {
    xstream = new XStream();

    try {
      List<Class> classes = findMyTypes("org.powertac.common");
      for (Class clazz : classes) {
        log.info("processing class " + clazz.getName());
        xstream.processAnnotations(clazz);
      }
    } catch (IOException e) {
      log.error("failed to process annotation", e);
    } catch (ClassNotFoundException e) {
      log.error("failed to process annotation", e);
    }

    for (Class commandClazz : commandClasses) {
      xstream.processAnnotations(commandClazz);
    }

    xstream.autodetectAnnotations(true);
  }

  public String toXML(Object message) {
    return xstream.toXML(message);
  }

  public Object fromXML(String xml) {
    return xstream.fromXML(xml);
  }
    
  @SuppressWarnings("rawtypes")
  private List<Class> findMyTypes(String basePackage) throws IOException, ClassNotFoundException
  {
      ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
      MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resourcePatternResolver);

      List<Class> candidates = new ArrayList<Class>();
      String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
                                 resolveBasePackage(basePackage) + "/" + "**/*.class";
      Resource[] resources = resourcePatternResolver.getResources(packageSearchPath);
      for (Resource resource : resources) {
          if (resource.isReadable()) {
              MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
              candidates.add(Class.forName(metadataReader.getClassMetadata().getClassName()));
          }
      }
      return candidates;
  }

  private String resolveBasePackage(String basePackage) {
      return ClassUtils.convertClassNameToResourcePath(SystemPropertyUtils.resolvePlaceholders(basePackage));
  }
}
