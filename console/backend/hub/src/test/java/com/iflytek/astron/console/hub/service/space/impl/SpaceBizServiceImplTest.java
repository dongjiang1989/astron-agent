package com.iflytek.astron.console.hub.service.space.impl;

import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.dto.space.SpaceUpdateDTO;
import com.iflytek.astron.console.commons.entity.space.Space;
import com.iflytek.astron.console.commons.entity.space.SpaceUser;
import com.iflytek.astron.console.commons.enums.space.SpaceRoleEnum;
import com.iflytek.astron.console.commons.enums.space.SpaceTypeEnum;
import com.iflytek.astron.console.commons.response.ApiResult;
import com.iflytek.astron.console.commons.service.bot.ChatBotDataService;
import com.iflytek.astron.console.commons.service.space.SpaceService;
import com.iflytek.astron.console.commons.service.space.SpaceUserService;
import com.iflytek.astron.console.commons.util.RequestContextUtil;
import com.iflytek.astron.console.commons.util.space.EnterpriseInfoUtil;
import com.iflytek.astron.console.commons.util.space.SpaceInfoUtil;
import com.iflytek.astron.console.hub.properties.SpaceLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SpaceBizServiceImpl
 * Focus on deleteSpace and updateSpace ownership/authorization checks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpaceBizServiceImpl Unit Tests")
class SpaceBizServiceImplTest {

    @Mock
    private SpaceUserService spaceUserService;
    @Mock
    private SpaceService spaceService;
    @Mock
    private ChatBotDataService chatBotDataService;
    @Mock
    private SpaceLimitProperties spaceLimitProperties;

    @InjectMocks
    private SpaceBizServiceImpl spaceBizService;

    private static final String TEST_UID = "test-uid-123";
    private static final Long TEST_SPACE_ID = 100L;
    private static final Long TEST_ENTERPRISE_ID = 1L;
    private static final Long OTHER_ENTERPRISE_ID = 2L;
    private static final Integer OWNER_ROLE = SpaceRoleEnum.OWNER.getCode();
    private static final Integer MEMBER_ROLE = SpaceRoleEnum.MEMBER.getCode();

    private Space personalSpace;
    private Space enterpriseSpace;
    private SpaceUser ownerSpaceUser;
    private SpaceUser memberSpaceUser;

    @BeforeEach
    void setUp() {
        personalSpace = new Space();
        personalSpace.setId(TEST_SPACE_ID);
        personalSpace.setName("Personal Space");
        personalSpace.setUid(TEST_UID);
        personalSpace.setType(SpaceTypeEnum.FREE.getCode());
        personalSpace.setEnterpriseId(null);

        enterpriseSpace = new Space();
        enterpriseSpace.setId(TEST_SPACE_ID);
        enterpriseSpace.setName("Enterprise Space");
        enterpriseSpace.setUid(TEST_UID);
        enterpriseSpace.setType(SpaceTypeEnum.ENTERPRISE.getCode());
        enterpriseSpace.setEnterpriseId(TEST_ENTERPRISE_ID);

        ownerSpaceUser = new SpaceUser();
        ownerSpaceUser.setId(1L);
        ownerSpaceUser.setSpaceId(TEST_SPACE_ID);
        ownerSpaceUser.setUid(TEST_UID);
        ownerSpaceUser.setRole(OWNER_ROLE);

        memberSpaceUser = new SpaceUser();
        memberSpaceUser.setId(2L);
        memberSpaceUser.setSpaceId(TEST_SPACE_ID);
        memberSpaceUser.setUid(TEST_UID);
        memberSpaceUser.setRole(MEMBER_ROLE);
    }

    // ==================== deleteSpace() - Enterprise Space Tests ====================

    @Test
    @DisplayName("deleteSpace - Should successfully delete enterprise space when enterpriseId matches")
    void deleteSpace_Success_WhenEnterpriseIdMatches() {
        try (MockedStatic<RequestContextUtil> mockedRequestContext = mockStatic(RequestContextUtil.class);
             MockedStatic<EnterpriseInfoUtil> mockedEnterpriseInfo = mockStatic(EnterpriseInfoUtil.class)) {

            mockedRequestContext.when(RequestContextUtil::getUID).thenReturn(TEST_UID);
            mockedRequestContext.when(RequestContextUtil::getCurrentRequest).thenReturn(mock(HttpServletRequest.class));
            mockedEnterpriseInfo.when(EnterpriseInfoUtil::getEnterpriseId).thenReturn(TEST_ENTERPRISE_ID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(enterpriseSpace);
            when(spaceService.removeById(TEST_SPACE_ID)).thenReturn(true);

            ApiResult<String> result = spaceBizService.deleteSpace(TEST_SPACE_ID, null, null);

            assertNotNull(result);
            assertEquals(ResponseEnum.SUCCESS.getCode(), result.code());
            verify(chatBotDataService).deleteBotForDeleteSpace(eq(TEST_UID), eq(TEST_SPACE_ID), any());
        }
    }

    @Test
    @DisplayName("deleteSpace - Should return error when enterprise space belongs to different enterprise")
    void deleteSpace_Error_WhenEnterpriseIdMismatch() {
        try (MockedStatic<EnterpriseInfoUtil> mockedEnterpriseInfo = mockStatic(EnterpriseInfoUtil.class)) {
            mockedEnterpriseInfo.when(EnterpriseInfoUtil::getEnterpriseId).thenReturn(OTHER_ENTERPRISE_ID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(enterpriseSpace);

            ApiResult<String> result = spaceBizService.deleteSpace(TEST_SPACE_ID, null, null);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_USER_SPACE_NOT_BELONG_TO_ENTERPRISE.getCode(), result.code());
            verify(spaceService, never()).removeById(anyLong());
            verify(chatBotDataService, never()).deleteBotForDeleteSpace(any(), anyLong(), any());
        }
    }

    @Test
    @DisplayName("deleteSpace - Should return error when enterprise space exists but current enterpriseId is null")
    void deleteSpace_Error_WhenCurrentEnterpriseIdIsNull() {
        try (MockedStatic<EnterpriseInfoUtil> mockedEnterpriseInfo = mockStatic(EnterpriseInfoUtil.class)) {
            mockedEnterpriseInfo.when(EnterpriseInfoUtil::getEnterpriseId).thenReturn(null);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(enterpriseSpace);

            ApiResult<String> result = spaceBizService.deleteSpace(TEST_SPACE_ID, null, null);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_USER_SPACE_NOT_BELONG_TO_ENTERPRISE.getCode(), result.code());
            verify(spaceService, never()).removeById(anyLong());
        }
    }

    // ==================== deleteSpace() - Personal Space Tests ====================

    @Test
    @DisplayName("deleteSpace - Should successfully delete personal space when current user is owner")
    void deleteSpace_Success_WhenPersonalSpaceOwner() {
        try (MockedStatic<RequestContextUtil> mockedRequestContext = mockStatic(RequestContextUtil.class)) {
            mockedRequestContext.when(RequestContextUtil::getUID).thenReturn(TEST_UID);
            mockedRequestContext.when(RequestContextUtil::getCurrentRequest).thenReturn(mock(HttpServletRequest.class));
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(personalSpace);
            when(spaceUserService.getSpaceUserByUid(TEST_SPACE_ID, TEST_UID)).thenReturn(ownerSpaceUser);
            when(spaceService.removeById(TEST_SPACE_ID)).thenReturn(true);

            ApiResult<String> result = spaceBizService.deleteSpace(TEST_SPACE_ID, null, null);

            assertNotNull(result);
            assertEquals(ResponseEnum.SUCCESS.getCode(), result.code());
            verify(chatBotDataService).deleteBotForDeleteSpace(eq(TEST_UID), eq(TEST_SPACE_ID), any());
        }
    }

    @Test
    @DisplayName("deleteSpace - Should return error when personal space user is not owner")
    void deleteSpace_Error_WhenPersonalSpaceNotOwner() {
        try (MockedStatic<RequestContextUtil> mockedRequestContext = mockStatic(RequestContextUtil.class)) {
            mockedRequestContext.when(RequestContextUtil::getUID).thenReturn(TEST_UID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(personalSpace);
            when(spaceUserService.getSpaceUserByUid(TEST_SPACE_ID, TEST_UID)).thenReturn(memberSpaceUser);

            ApiResult<String> result = spaceBizService.deleteSpace(TEST_SPACE_ID, null, null);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_USER_NOT_OWNER.getCode(), result.code());
            verify(spaceService, never()).removeById(anyLong());
            verify(chatBotDataService, never()).deleteBotForDeleteSpace(any(), anyLong(), any());
        }
    }

    @Test
    @DisplayName("deleteSpace - Should return error when personal space user is not in space at all")
    void deleteSpace_Error_WhenPersonalSpaceUserNotInSpace() {
        try (MockedStatic<RequestContextUtil> mockedRequestContext = mockStatic(RequestContextUtil.class)) {
            mockedRequestContext.when(RequestContextUtil::getUID).thenReturn(TEST_UID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(personalSpace);
            when(spaceUserService.getSpaceUserByUid(TEST_SPACE_ID, TEST_UID)).thenReturn(null);

            ApiResult<String> result = spaceBizService.deleteSpace(TEST_SPACE_ID, null, null);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_USER_NOT_OWNER.getCode(), result.code());
            verify(spaceService, never()).removeById(anyLong());
        }
    }

    // ==================== deleteSpace() - Common Tests ====================

    @Test
    @DisplayName("deleteSpace - Should return error when space does not exist")
    void deleteSpace_Error_WhenSpaceNotExists() {
        when(spaceService.getById(TEST_SPACE_ID)).thenReturn(null);

        ApiResult<String> result = spaceBizService.deleteSpace(TEST_SPACE_ID, null, null);

        assertNotNull(result);
        assertEquals(ResponseEnum.SPACE_NOT_EXISTS.getCode(), result.code());
        verify(spaceService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("deleteSpace - Should return error when delete fails after passing authorization")
    void deleteSpace_Error_WhenDeleteFails() {
        try (MockedStatic<RequestContextUtil> mockedRequestContext = mockStatic(RequestContextUtil.class);
             MockedStatic<EnterpriseInfoUtil> mockedEnterpriseInfo = mockStatic(EnterpriseInfoUtil.class)) {

            mockedRequestContext.when(RequestContextUtil::getUID).thenReturn(TEST_UID);
            mockedRequestContext.when(RequestContextUtil::getCurrentRequest).thenReturn(mock(HttpServletRequest.class));
            mockedEnterpriseInfo.when(EnterpriseInfoUtil::getEnterpriseId).thenReturn(TEST_ENTERPRISE_ID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(enterpriseSpace);
            when(spaceService.removeById(TEST_SPACE_ID)).thenReturn(false);

            ApiResult<String> result = spaceBizService.deleteSpace(TEST_SPACE_ID, null, null);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_DELETE_FAILED.getCode(), result.code());
            verify(chatBotDataService, never()).deleteBotForDeleteSpace(any(), anyLong(), any());
        }
    }

    // ==================== updateSpace() - Enterprise Space Tests ====================

    @Test
    @DisplayName("updateSpace - Should successfully update enterprise space when enterpriseId matches")
    void updateSpace_Success_WhenEnterpriseSpaceBelongsToEnterprise() {
        try (MockedStatic<EnterpriseInfoUtil> mockedEnterpriseInfo = mockStatic(EnterpriseInfoUtil.class)) {
            mockedEnterpriseInfo.when(EnterpriseInfoUtil::getEnterpriseId).thenReturn(TEST_ENTERPRISE_ID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(enterpriseSpace);
            when(spaceService.checkExistByName("Updated Space", TEST_SPACE_ID)).thenReturn(false);
            when(spaceService.updateById(any(Space.class))).thenReturn(true);

            SpaceUpdateDTO dto = new SpaceUpdateDTO();
            dto.setId(TEST_SPACE_ID);
            dto.setName("Updated Space");
            dto.setDescription("Updated description");

            ApiResult<String> result = spaceBizService.updateSpace(dto);

            assertNotNull(result);
            assertEquals(ResponseEnum.SUCCESS.getCode(), result.code());
            verify(spaceService).updateById(any(Space.class));
        }
    }

    @Test
    @DisplayName("updateSpace - Should return error when enterprise space belongs to different enterprise")
    void updateSpace_Error_WhenEnterpriseSpaceBelongsToDifferentEnterprise() {
        try (MockedStatic<EnterpriseInfoUtil> mockedEnterpriseInfo = mockStatic(EnterpriseInfoUtil.class)) {
            mockedEnterpriseInfo.when(EnterpriseInfoUtil::getEnterpriseId).thenReturn(OTHER_ENTERPRISE_ID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(enterpriseSpace);

            SpaceUpdateDTO dto = new SpaceUpdateDTO();
            dto.setId(TEST_SPACE_ID);
            dto.setName("Updated Space");

            ApiResult<String> result = spaceBizService.updateSpace(dto);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_USER_SPACE_NOT_BELONG_TO_ENTERPRISE.getCode(), result.code());
            verify(spaceService, never()).updateById(any());
        }
    }

    @Test
    @DisplayName("updateSpace - Should return error when enterprise space exists but current enterpriseId is null")
    void updateSpace_Error_WhenEnterpriseSpaceCurrentEnterpriseIdNull() {
        try (MockedStatic<EnterpriseInfoUtil> mockedEnterpriseInfo = mockStatic(EnterpriseInfoUtil.class)) {
            mockedEnterpriseInfo.when(EnterpriseInfoUtil::getEnterpriseId).thenReturn(null);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(enterpriseSpace);

            SpaceUpdateDTO dto = new SpaceUpdateDTO();
            dto.setId(TEST_SPACE_ID);
            dto.setName("Updated Space");

            ApiResult<String> result = spaceBizService.updateSpace(dto);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_USER_SPACE_NOT_BELONG_TO_ENTERPRISE.getCode(), result.code());
        }
    }

    // ==================== updateSpace() - Personal Space Tests ====================

    @Test
    @DisplayName("updateSpace - Should successfully update personal space when header spaceId matches DTO id")
    void updateSpace_Success_WhenPersonalSpaceHeaderMatchesDto() {
        try (MockedStatic<SpaceInfoUtil> mockedSpaceInfo = mockStatic(SpaceInfoUtil.class)) {
            mockedSpaceInfo.when(SpaceInfoUtil::getSpaceId).thenReturn(TEST_SPACE_ID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(personalSpace);
            when(spaceService.checkExistByName("Updated Space", TEST_SPACE_ID)).thenReturn(false);
            when(spaceService.updateById(any(Space.class))).thenReturn(true);

            SpaceUpdateDTO dto = new SpaceUpdateDTO();
            dto.setId(TEST_SPACE_ID);
            dto.setName("Updated Space");

            ApiResult<String> result = spaceBizService.updateSpace(dto);

            assertNotNull(result);
            assertEquals(ResponseEnum.SUCCESS.getCode(), result.code());
        }
    }

    @Test
    @DisplayName("updateSpace - Should return error when personal space header spaceId mismatches DTO id")
    void updateSpace_Error_WhenPersonalSpaceHeaderMismatchDto() {
        try (MockedStatic<SpaceInfoUtil> mockedSpaceInfo = mockStatic(SpaceInfoUtil.class)) {
            mockedSpaceInfo.when(SpaceInfoUtil::getSpaceId).thenReturn(200L);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(personalSpace);

            SpaceUpdateDTO dto = new SpaceUpdateDTO();
            dto.setId(TEST_SPACE_ID);
            dto.setName("Updated Space");

            ApiResult<String> result = spaceBizService.updateSpace(dto);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_APPLICATION_CURRENT_SPACE_INCONSISTENT.getCode(), result.code());
            verify(spaceService, never()).updateById(any());
        }
    }

    @Test
    @DisplayName("updateSpace - Should return error when personal space header spaceId is null")
    void updateSpace_Error_WhenPersonalSpaceHeaderSpaceIdNull() {
        try (MockedStatic<SpaceInfoUtil> mockedSpaceInfo = mockStatic(SpaceInfoUtil.class)) {
            mockedSpaceInfo.when(SpaceInfoUtil::getSpaceId).thenReturn(null);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(personalSpace);

            SpaceUpdateDTO dto = new SpaceUpdateDTO();
            dto.setId(TEST_SPACE_ID);
            dto.setName("Updated Space");

            ApiResult<String> result = spaceBizService.updateSpace(dto);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_APPLICATION_CURRENT_SPACE_INCONSISTENT.getCode(), result.code());
        }
    }

    // ==================== updateSpace() - Common Tests ====================

    @Test
    @DisplayName("updateSpace - Should return error when space does not exist")
    void updateSpace_Error_WhenSpaceNotExists() {
        when(spaceService.getById(TEST_SPACE_ID)).thenReturn(null);

        SpaceUpdateDTO dto = new SpaceUpdateDTO();
        dto.setId(TEST_SPACE_ID);
        dto.setName("Updated Space");

        ApiResult<String> result = spaceBizService.updateSpace(dto);

        assertNotNull(result);
        assertEquals(ResponseEnum.SPACE_NOT_EXISTS.getCode(), result.code());
        verify(spaceService, never()).updateById(any());
    }

    @Test
    @DisplayName("updateSpace - Should return error when name already exists")
    void updateSpace_Error_WhenNameDuplicate() {
        try (MockedStatic<SpaceInfoUtil> mockedSpaceInfo = mockStatic(SpaceInfoUtil.class)) {
            mockedSpaceInfo.when(SpaceInfoUtil::getSpaceId).thenReturn(TEST_SPACE_ID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(personalSpace);
            when(spaceService.checkExistByName("Duplicate Name", TEST_SPACE_ID)).thenReturn(true);

            SpaceUpdateDTO dto = new SpaceUpdateDTO();
            dto.setId(TEST_SPACE_ID);
            dto.setName("Duplicate Name");

            ApiResult<String> result = spaceBizService.updateSpace(dto);

            assertNotNull(result);
            assertEquals(ResponseEnum.SPACE_NAME_DUPLICATE.getCode(), result.code());
        }
    }

    @Test
    @DisplayName("updateSpace - Should return error when update fails after passing checks")
    void updateSpace_Error_WhenUpdateFails() {
        try (MockedStatic<SpaceInfoUtil> mockedSpaceInfo = mockStatic(SpaceInfoUtil.class)) {
            mockedSpaceInfo.when(SpaceInfoUtil::getSpaceId).thenReturn(TEST_SPACE_ID);
            when(spaceService.getById(TEST_SPACE_ID)).thenReturn(personalSpace);
            when(spaceService.checkExistByName("Updated Space", TEST_SPACE_ID)).thenReturn(false);
            when(spaceService.updateById(any(Space.class))).thenReturn(false);

            SpaceUpdateDTO dto = new SpaceUpdateDTO();
            dto.setId(TEST_SPACE_ID);
            dto.setName("Updated Space");

            ApiResult<String> result = spaceBizService.updateSpace(dto);

            assertNotNull(result);
            assertEquals(ResponseEnum.ENTERPRISE_UPDATE_FAILED.getCode(), result.code());
        }
    }
}
