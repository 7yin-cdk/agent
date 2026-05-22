package com.library.agent.mapper;

import com.library.agent.entity.AgentUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentUserMapper {

    int insert(AgentUser user);

    AgentUser selectById(@Param("id") Long id);

    AgentUser selectByUsername(@Param("username") String username);
}
